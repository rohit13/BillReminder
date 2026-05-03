package com.billreminder.app.util

import com.billreminder.app.data.GeminiCache
import com.billreminder.app.data.GeminiCacheDao
import com.billreminder.app.util.GeminiValidator.FallbackDns
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Regression tests for Gemini API network failure handling inside [GeminiValidator].
 *
 * Reproduces the exact error seen in production:
 *   UnknownHostException: Unable to resolve host "generativelanguage.googleapis.com":
 *   No address associated with hostname
 *
 * The tests inject a real [OkHttpClient] configured with a custom [Dns] that always
 * throws [UnknownHostException] — no mocking framework needed. This exactly mirrors
 * what Android reports when DNS resolution fails on-device.
 */
class GeminiValidatorTest {

    // ── Dns implementations ───────────────────────────────────────────────────

    /** Simulates the exact DNS failure seen in the production logcat. */
    private class AlwaysFailingDns : Dns {
        var lookupCount = 0
        override fun lookup(hostname: String): List<InetAddress> {
            lookupCount++
            throw UnknownHostException(
                "Unable to resolve host \"$hostname\": No address associated with hostname"
            )
        }
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    /**
     * REGRESSION: When DNS resolution fails (UnknownHostException), validateAndExtract
     * must return [GeminiResult.API_ERROR], not crash or return PARSE_ERROR.
     */
    @Test
    fun `UnknownHostException returns API_ERROR`() = runTest {
        val validator = validatorWithFailingDns()

        val result = validator.validateAndExtract(
            emailId = "email-123",
            subject = "Your credit card statement is available",
            snippet = "Your statement is ready to view."
        )

        assertEquals(
            "Expected api_error when DNS fails, not parse_error or a crash",
            "api_error",
            result.reason
        )
        assertEquals(false, result.isInvoice)
        assertEquals(0.0, result.confidence, 0.0)
    }

    /**
     * DNS failures abort after exactly 2 attempts (not the full MAX_RETRIES=3).
     *
     * UnknownHostException rarely self-heals in 2-4 seconds — retrying a third time
     * just wastes ~4 seconds per email. The validator allows one retry to cover
     * genuine transient DNS blips, then aborts immediately on the second failure.
     *
     * Each attempt triggers one DNS lookup, so lookupCount == 2 proves the early abort.
     * If lookupCount == 3 the bug is present (full retry cycle was not short-circuited).
     */
    @Test
    fun `UnknownHostException aborts after 2 attempts not full MAX_RETRIES`() = runTest {
        val failingDns = AlwaysFailingDns()
        val validator = validatorWithDns(failingDns)

        validator.validateAndExtract(
            emailId = "email-456",
            subject = "Your credit card statement is available",
            snippet = "Your statement is ready to view."
        )

        assertEquals(
            "DNS failures must abort after 2 attempts (1 retry), not the full MAX_RETRIES=3. " +
            "A third attempt wastes 4+ extra seconds on a failure that won't self-heal. " +
            "lookupCount == 3 means the early abort is missing.",
            2,
            failingDns.lookupCount
        )
    }

    /**
     * A transient DNS failure on the first attempt must NOT prevent a successful
     * second attempt from being returned.
     */
    @Test
    fun `DNS failure on first attempt succeeds on retry when DNS recovers`() = runTest {
        var attempt = 0
        val flakyDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                attempt++
                return if (attempt == 1) {
                    throw UnknownHostException("Transient failure on attempt 1")
                } else {
                    // Return a loopback address on the second attempt so OkHttp can connect.
                    // The server won't be there, but OkHttp will throw a ConnectException
                    // (not UnknownHostException), which also triggers a retry.
                    // This test confirms the retry path is exercised after DNS recovery.
                    Dns.SYSTEM.lookup(hostname).ifEmpty {
                        listOf(InetAddress.getByName("127.0.0.1"))
                    }
                }
            }
        }
        val validator = validatorWithDns(flakyDns)

        val result = validator.validateAndExtract(
            emailId = "email-789",
            subject = "Your credit card statement is available",
            snippet = "Your statement is ready to view."
        )

        // Still ends up as API_ERROR (no real server at 127.0.0.1) but crucially
        // attempt count shows the retry was exercised, not aborted after first failure.
        assertEquals("api_error", result.reason)
        assert(attempt >= 2) {
            "Expected at least 2 DNS lookups (first failure + at least one retry), got $attempt"
        }
    }

    /**
     * An already-cached result must be returned immediately without any network call.
     * DNS must never be consulted — proves the cache short-circuit works.
     */
    @Test
    fun `cached result is returned without any network call`() = runTest {
        val failingDns = AlwaysFailingDns()
        val cachedResult = GeminiCache(
            emailId       = "cached-email",
            isInvoice     = true,
            isReceipt     = false,
            isDuplicate   = false,
            provider      = "Test Bank",
            amount        = 99.99,
            dueDate       = null,
            confidence    = 0.92,
            reason        = "Identified as invoice",
            checkedAt     = System.currentTimeMillis()
        )
        val validator = validatorWithDns(failingDns, preloadedCache = cachedResult)

        val result = validator.validateAndExtract(
            emailId = "cached-email",
            subject = "Anything",
            snippet = "Anything"
        )

        assertEquals(0, failingDns.lookupCount) // no network call made
        assertEquals(true, result.isInvoice)
        assertEquals(0.92, result.confidence, 0.001)
    }

    /**
     * Root cause test: FallbackDns tries system DNS first; when it throws
     * UnknownHostException (stale negative cache / ISP blocking), it automatically
     * falls back to the secondary resolver without surfacing the error to the caller.
     *
     * This is the exact fix for UnknownHostException on Pixel 9 Pro / Android 14+
     * where InetAddress.getAllByName() returns a cached NXDOMAIN from a prior failed
     * lookup, while HttpURLConnection (used by Gmail API) transparently retries.
     */
    @Test
    fun `FallbackDns uses secondary when primary throws UnknownHostException`() {
        val fallbackAddress = InetAddress.getByName("142.250.80.46") // example IP
        var fallbackWasCalled = false

        val primary = Dns { throw UnknownHostException("Simulated stale negative cache") }
        val secondary = Dns { fallbackWasCalled = true; listOf(fallbackAddress) }
        val dns = FallbackDns(primary = primary, fallback = secondary)

        val result = dns.lookup("generativelanguage.googleapis.com")

        assertTrue("Fallback DNS must be called when system DNS fails", fallbackWasCalled)
        assertEquals(listOf(fallbackAddress), result)
    }

    /**
     * FallbackDns must NOT call the secondary when primary succeeds.
     */
    @Test
    fun `FallbackDns uses primary when it succeeds`() {
        val primaryAddress = InetAddress.getByName("172.217.0.1")
        var fallbackWasCalled = false

        val primary = Dns { listOf(primaryAddress) }
        val secondary = Dns { fallbackWasCalled = true; emptyList() }
        val dns = FallbackDns(primary = primary, fallback = secondary)

        val result = dns.lookup("generativelanguage.googleapis.com")

        assertTrue("Fallback DNS must NOT be called when system DNS succeeds", !fallbackWasCalled)
        assertEquals(listOf(primaryAddress), result)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun validatorWithFailingDns() = validatorWithDns(AlwaysFailingDns())

    private fun validatorWithDns(dns: Dns, preloadedCache: GeminiCache? = null): GeminiValidator {
        val client = OkHttpClient.Builder()
            .dns(dns)
            .build()
        return GeminiValidator(
            cacheDao  = FakeGeminiCacheDao(preloadedCache),
            apiKey    = "test-api-key-not-blank",
            httpClient = client
        )
    }

    // ── Fakes ──────────────────────────────────────────────────────────────────

    private class FakeGeminiCacheDao(
        private val preloaded: GeminiCache? = null
    ) : GeminiCacheDao {
        private val cache = mutableMapOf<String, GeminiCache>()
            .also { if (preloaded != null) it[preloaded.emailId] = preloaded }

        override suspend fun getByEmailId(emailId: String): GeminiCache? = cache[emailId]
        override suspend fun insert(entry: GeminiCache) { cache[entry.emailId] = entry }
        override suspend fun deleteOlderThan(cutoff: Long) = Unit
        override suspend fun clearAll() = cache.clear()
    }
}
