package com.billreminder.app.util

import android.util.Log
import com.billreminder.app.BuildConfig
import com.billreminder.app.data.GeminiCache
import com.billreminder.app.data.GeminiCacheDao
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Stage 2 of the bill identification pipeline.
 *
 * Calls the Gemini REST API (v1beta) directly to classify an email as an invoice
 * and extract structured bill data in a single call. Using the REST API instead of
 * the generativeai SDK avoids the SDK's abandoned dependency on a broken serializer
 * (MissingFieldException on error responses) and the deprecated model alias.
 *
 * Results are persisted in [GeminiCacheDao] so subsequent syncs never re-call the
 * API for an already-evaluated email — including rejected ones.
 */
open class GeminiValidator(
    private val cacheDao: GeminiCacheDao,
    /** Injectable for testing; defaults to the build-time key in production. */
    internal val apiKey: String = BuildConfig.GEMINI_API_KEY,
    /** Injectable for testing; defaults to DoH-backed client in production. */
    private val httpClient: OkHttpClient = buildDefaultHttpClient()
) {
    private val gson = Gson()

    /**
     * Validates whether [emailId] is a payable invoice and extracts bill fields.
     *
     * 1. Checks [cacheDao] for a prior result — returns immediately on cache hit.
     * 2. On cache miss, calls Gemini REST API with a structured JSON prompt.
     * 3. Persists the result (accept or reject) to [cacheDao] before returning.
     *
     * Returns [GeminiResult.API_ERROR] on network/HTTP failures and
     * [GeminiResult.PARSE_ERROR] on JSON parse failures so Stage 3 rejects the
     * email without crashing. Neither is cached so the next sync retries.
     */
    open suspend fun validateAndExtract(
        emailId: String,
        subject: String,
        snippet: String
    ): GeminiResult {
        if (apiKey.isBlank()) {
            Log.e(TAG, "Gemini API key missing — skipping validation")
            return GeminiResult.API_ERROR
        }

        // Cache check
        val cached = cacheDao.getByEmailId(emailId)
        if (cached != null) {
            Log.d(TAG, "Cache hit for $emailId (isInvoice=${cached.isInvoice}, confidence=${cached.confidence})")
            return cached.toGeminiResult()
        }

        // API call
        val result = callGeminiApi(subject, snippet)
        Log.d(TAG, "Gemini result for '$subject': isInvoice=${result.isInvoice}, confidence=${result.confidence}, reason=${result.reason}")

        // Only cache definitive results — do NOT cache parse errors or API failures.
        // Transient errors should be retried on the next sync, not permanently rejected.
        if (result.reason != "parse_error" && result.reason != "api_error") {
            cacheDao.insert(result.toGeminiCache(emailId))
        } else {
            Log.w(TAG, "Skipping cache for ${result.reason} on emailId=$emailId — will retry next sync")
        }

        return result
    }

    private suspend fun callGeminiApi(subject: String, snippet: String): GeminiResult {
        val prompt = buildPrompt(subject, snippet)

        // Request JSON-mode output so the model returns pure JSON without markdown fences.
        val requestBodyJson = gson.toJson(
            mapOf(
                "contents" to listOf(
                    mapOf("parts" to listOf(mapOf("text" to prompt)))
                ),
                "generationConfig" to mapOf(
                    "responseMimeType" to "application/json"
                )
            )
        )

        var lastError: String? = null
        repeat(MAX_RETRIES) { attempt ->
            if (attempt > 0) {
                val backoffMs = RETRY_BACKOFF_MS * (1L shl (attempt - 1)) // 2s, 4s
                Log.w(TAG, "Retrying Gemini API (attempt ${attempt + 1}/$MAX_RETRIES) after ${backoffMs}ms for '$subject'")
                delay(backoffMs)
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$BASE_URL/$MODEL:generateContent?key=$apiKey")
                        .post(requestBodyJson.toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        when {
                            response.isSuccessful -> parseApiResponse(body, subject)
                            response.code in 400..499 && response.code != 429 -> {
                                // 4xx errors (except rate-limit) are permanent — don't retry
                                Log.e(TAG, "Gemini HTTP ${response.code} (permanent) for '$subject': $body")
                                return GeminiResult.API_ERROR
                            }
                            else -> {
                                // 5xx or 429 — transient, allow retry
                                Log.e(TAG, "Gemini HTTP ${response.code} on attempt ${attempt + 1} for '$subject': $body")
                                lastError = "HTTP ${response.code}"
                                null
                            }
                        }
                    }
                }
                if (result != null) return result
            } catch (e: UnknownHostException) {
                // DNS resolution failed — this rarely self-heals within seconds.
                // Allow one retry (covers genuine transient DNS blips), then abort
                // immediately rather than burning 4 more seconds on a hopeless attempt.
                Log.e(TAG, "Gemini DNS failure (attempt ${attempt + 1}/$MAX_RETRIES) for '$subject': " +
                    "Cannot resolve '${e.message}'. " +
                    "Check Settings → Network & internet → Private DNS on the device, " +
                    "or verify the domain is not blocked on your network.", e)
                lastError = "DNS: ${e.message}"
                if (attempt >= 1) {
                    Log.e(TAG, "Aborting Gemini retries after persistent DNS failure for '$subject'")
                    return GeminiResult.API_ERROR
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gemini network error (attempt ${attempt + 1}/$MAX_RETRIES) for '$subject': ${e.javaClass.simpleName}: ${e.message}", e)
                lastError = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
        Log.e(TAG, "All Gemini attempts failed for '$subject': $lastError")
        return GeminiResult.API_ERROR
    }

    private fun parseApiResponse(body: String, subject: String): GeminiResult {
        return try {
            val apiResponse = gson.fromJson(body, GeminiApiResponse::class.java)
            val text = apiResponse.candidates
                ?.firstOrNull()
                ?.content
                ?.parts
                ?.firstOrNull()
                ?.text
                ?.trim()
            if (text.isNullOrEmpty()) {
                Log.e(TAG, "Gemini returned empty response text for '$subject'. Body: $body")
                return GeminiResult.PARSE_ERROR
            }
            parseGeminiJson(text, subject)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Gemini API response for '$subject': ${e.message}. Body: $body", e)
            GeminiResult.PARSE_ERROR
        }
    }

    private fun parseGeminiJson(text: String, subject: String): GeminiResult {
        // JSON-mode ensures the response is already JSON, but extractJsonObject acts
        // as a safety net in case the model wraps output in any extra text.
        val jsonString = extractJsonObject(text) ?: run {
            Log.e(TAG, "No JSON object in Gemini response for '$subject': $text")
            return GeminiResult.PARSE_ERROR
        }

        return try {
            val parsed = gson.fromJson(jsonString, GeminiJsonResponse::class.java)
            GeminiResult(
                isInvoice = parsed.isInvoice,
                isReceipt = parsed.isReceipt,
                isDuplicate = parsed.isDuplicate,
                provider = parsed.provider?.takeIf { it.isNotBlank() },
                amount = parsed.amount?.takeIf { it > 0 && it < 100_000 },
                dueDate = parsed.dueDate?.takeIf { it.matches(ISO_DATE_REGEX) },
                confidence = parsed.confidence.coerceIn(0.0, 1.0),
                reason = parsed.reason
            )
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error for '$subject'. Input: $jsonString", e)
            GeminiResult.PARSE_ERROR
        }
    }

    /**
     * Finds and returns the first balanced JSON object `{...}` in [text].
     * Handles nested objects by tracking brace depth.
     * Returns null if no valid JSON object is found.
     */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start == -1) return null

        var depth = 0
        var inString = false
        var escape = false

        for (i in start until text.length) {
            val c = text[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun buildPrompt(subject: String, snippet: String): String = """
        You are a precise bill and invoice classifier for a personal finance app.
        Analyze the email subject and body snippet below.

        Respond ONLY with a valid JSON object — no markdown, no explanation, no code block.
        Use this exact schema:
        {
          "isInvoice": boolean,
          "isReceipt": boolean,
          "isDuplicate": boolean,
          "provider": string or null,
          "amount": number or null,
          "dueDate": "YYYY-MM-DD" or null,
          "confidence": number between 0.0 and 1.0,
          "reason": string
        }

        Field meanings:
        - isInvoice: true only if a payment is currently owed by the recipient
        - isReceipt: true if this email confirms a payment already made
        - isDuplicate: true if this looks like a repeat reminder for a bill already seen
        - provider: the company or service name (e.g. "Netflix", "AT&T")
        - amount: the numeric amount owed, without currency symbol (e.g. 29.99)
        - dueDate: the payment due date in ISO-8601 format, or null
        - confidence: your confidence that isInvoice is correct (0.0 = unsure, 1.0 = certain)
        - reason: one short sentence explaining your classification

        Set isInvoice=false for:
        - Statements with ${'$'}0 balance
        - Payment confirmations or receipts
        - Marketing, newsletters, policy updates
        - Informational notices without a specific amount due

        Subject: $subject
        Snippet: $snippet
    """.trimIndent()

    // ── REST API response POJOs ────────────────────────────────────────────────

    private data class GeminiApiResponse(
        @SerializedName("candidates") val candidates: List<Candidate>?
    )

    private data class Candidate(
        @SerializedName("content") val content: Content?
    )

    private data class Content(
        @SerializedName("parts") val parts: List<Part>?
    )

    private data class Part(
        @SerializedName("text") val text: String?
    )

    /** Typed POJO for the structured JSON payload returned by the model. */
    private data class GeminiJsonResponse(
        @SerializedName("isInvoice") val isInvoice: Boolean = false,
        @SerializedName("isReceipt") val isReceipt: Boolean = false,
        @SerializedName("isDuplicate") val isDuplicate: Boolean = false,
        @SerializedName("provider") val provider: String? = null,
        @SerializedName("amount") val amount: Double? = null,
        @SerializedName("dueDate") val dueDate: String? = null,
        @SerializedName("confidence") val confidence: Double = 0.0,
        @SerializedName("reason") val reason: String? = null
    )

    companion object {
        private const val TAG = "GeminiValidator"
        private const val MODEL = "gemini-2.5-flash"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private val ISO_DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_RETRIES = 3
        private const val RETRY_BACKOFF_MS = 2_000L

        /**
         * Production default: system DNS first, Google DoH (8.8.8.8) as fallback.
         *
         * Root cause of UnknownHostException on Pixel 9 Pro / Android 14+:
         *   Android's negative DNS cache stores a failed lookup result and serves
         *   it from cache on every subsequent call — even after the real DNS server
         *   would now succeed. ISP/carrier DNS servers are also slow to propagate
         *   newer Google AI domains like generativelanguage.googleapis.com.
         *
         * HttpURLConnection (used by the Gmail API) goes through Android's libcore
         *   network stack which has transparent retry logic. OkHttpClient with Dns.SYSTEM
         *   calls InetAddress.getAllByName() which hits the stale negative cache.
         *
         * Fix: fall back to DoH via Google's public DNS (8.8.8.8/8.8.4.4) when
         *   system DNS fails. Bootstrap IPs are hardcoded so the DoH connection itself
         *   needs no DNS lookup — bypassing both the negative cache and ISP filtering.
         */
        internal fun buildDefaultHttpClient(): OkHttpClient {
            val bootstrapClient = OkHttpClient()
            val googleDoH = DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url("https://dns.google/dns-query".toHttpUrl())
                .bootstrapDnsHosts(
                    InetAddress.getByName("8.8.8.8"),
                    InetAddress.getByName("8.8.4.4")
                )
                .includeIPv6(false)
                .build()

            return OkHttpClient.Builder()
                .dns(FallbackDns(primary = Dns.SYSTEM, fallback = googleDoH))
                .build()
        }

        /**
         * Tries [primary] DNS first. On [UnknownHostException] falls back to [fallback].
         * Logs which path was taken so it's visible in logcat.
         */
        internal class FallbackDns(
            private val primary: Dns,
            private val fallback: Dns
        ) : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return try {
                    primary.lookup(hostname).also {
                        Log.d(TAG, "DNS resolved '$hostname' via system DNS: $it")
                    }
                } catch (e: UnknownHostException) {
                    Log.w(TAG, "System DNS failed for '$hostname', falling back to Google DoH (8.8.8.8)")
                    fallback.lookup(hostname).also {
                        Log.d(TAG, "DNS resolved '$hostname' via Google DoH: $it")
                    }
                }
            }
        }
    }
}

// ── Extension helpers ──────────────────────────────────────────────────────────

fun GeminiCache.toGeminiResult() = GeminiResult(
    isInvoice = isInvoice,
    isReceipt = isReceipt,
    isDuplicate = isDuplicate,
    provider = provider,
    amount = amount,
    dueDate = dueDate,
    confidence = confidence,
    reason = reason
)

fun GeminiResult.toGeminiCache(emailId: String) = GeminiCache(
    emailId = emailId,
    isInvoice = isInvoice,
    isReceipt = isReceipt,
    isDuplicate = isDuplicate,
    provider = provider,
    amount = amount,
    dueDate = dueDate,
    confidence = confidence,
    reason = reason,
    checkedAt = System.currentTimeMillis()
)
