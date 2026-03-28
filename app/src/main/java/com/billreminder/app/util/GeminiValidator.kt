package com.billreminder.app.util

import android.util.Log
import com.billreminder.app.BuildConfig
import com.billreminder.app.data.GeminiCache
import com.billreminder.app.data.GeminiCacheDao
import com.google.ai.client.generativeai.GenerativeModel
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Stage 2 of the bill identification pipeline.
 *
 * Calls the Gemini API to classify an email as an invoice and simultaneously
 * extract structured bill data (amount, due date, provider) in a single API
 * call. Results are persisted in [GeminiCacheDao] so subsequent syncs never
 * re-call the API for an already-evaluated email — including rejected ones.
 *
 * Converted from the previous singleton `object` to a class so it can receive
 * the [GeminiCacheDao] dependency for cache read/write.
 */
class GeminiValidator(private val cacheDao: GeminiCacheDao) {

    private val generativeModel by lazy {
        GenerativeModel(
            modelName = "gemini-2.0-flash",
            apiKey = BuildConfig.GEMINI_API_KEY
        )
    }

    /**
     * Validates whether [emailId] is a payable invoice and extracts bill fields.
     *
     * 1. Checks [cacheDao] for a prior result — returns immediately on cache hit.
     * 2. On cache miss, calls Gemini API with a structured JSON prompt.
     * 3. Persists the result (accept or reject) to [cacheDao] before returning.
     *
     * Returns [GeminiResult.UNKNOWN] (confidence=0.0) on API/parse errors so
     * Stage 3 rejects the email without crashing.
     */
    suspend fun validateAndExtract(
        emailId: String,
        subject: String,
        snippet: String
    ): GeminiResult {
        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            Log.e(TAG, "Gemini API key missing — skipping validation")
            return GeminiResult.UNKNOWN
        }

        // Cache check
        val cached = cacheDao.getByEmailId(emailId)
        if (cached != null) {
            Log.d(TAG, "Cache hit for $emailId (isInvoice=${cached.isInvoice}, confidence=${cached.confidence})")
            return cached.toGeminiResult()
        }

        // API call
        val result = callGeminiApi(subject, snippet)
        Log.d(TAG, "Gemini result for '$subject': isInvoice=${result.isInvoice}, confidence=${result.confidence}")

        // Persist — cache both accepted and rejected so they are never re-evaluated
        cacheDao.insert(result.toGeminiCache(emailId))

        return result
    }

    private suspend fun callGeminiApi(subject: String, snippet: String): GeminiResult {
        val prompt = """
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

        return try {
            val response = generativeModel.generateContent(prompt)
            val text = response.text?.trim() ?: return GeminiResult.UNKNOWN
            parseGeminiJson(text)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API error for '$subject'", e)
            GeminiResult.UNKNOWN
        }
    }

    private fun parseGeminiJson(text: String): GeminiResult {
        // Strip accidental markdown fences (```json ... ```)
        val cleaned = text
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            val parsed = Gson().fromJson(cleaned, GeminiJsonResponse::class.java)
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
            Log.e(TAG, "JSON parse error: $cleaned", e)
            GeminiResult.UNKNOWN
        }
    }

    /** Typed POJO for Gson deserialization — avoids Boolean/String ambiguity from Map parsing. */
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
        private val ISO_DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
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
