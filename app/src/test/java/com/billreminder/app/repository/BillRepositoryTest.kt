package com.billreminder.app.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.billreminder.app.data.BillDao
import com.billreminder.app.data.GeminiCache
import com.billreminder.app.data.GeminiCacheDao
import com.billreminder.app.model.Bill
import com.billreminder.app.model.BillStatus
import com.billreminder.app.util.GeminiResult
import com.billreminder.app.util.GeminiValidator
import com.google.api.services.gmail.model.MessagePartHeader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Regression tests for the "api_error permanently rejects bill" bug.
 *
 * Bug:
 *   BillRepository.syncFromGmail only checked `reason == "parse_error"` before skipping
 *   storage. When Gemini returned `api_error` (network failure), the bill fell through to
 *   the else-branch and was stored as `isRejectedByGemini=true`. The dedup check on the
 *   next sync found that row and skipped the email — Gemini was never retried.
 *
 * Fix:
 *   The skip condition now covers both "parse_error" and "api_error", so network failures
 *   do NOT write to the database and the email is re-evaluated on every subsequent sync.
 *
 * These tests would FAIL on the old code and PASS on the fixed code.
 */
class BillRepositoryTest {

    // ── Test data ──────────────────────────────────────────────────────────────

    /** Email from an unknown domain → EmailPreFilter returns NeedsGemini → Gemini is called. */
    private val testEmail = ParsedEmailResult(
        bill = Bill(
            emailId = "test-email-abc123",
            subject  = "Your credit card statement is available",
            sender   = "Unknown Bank",
            senderEmail = "alerts@unknown-bank.com"
        ),
        headers = emptyList<MessagePartHeader>()
    )

    // ── Tests ──────────────────────────────────────────────────────────────────

    /**
     * REGRESSION: When Gemini returns api_error, the bill must NOT be written to the
     * database. If it were stored as rejected, the next sync's dedup check would find it
     * and silently skip re-evaluation — the email would be lost forever.
     *
     * Old code: fails — bill is stored with isRejectedByGemini=true
     * Fixed code: passes — bill is not stored at all
     */
    @Test
    fun `api_error from Gemini does not permanently reject the bill`() = runTest {
        val dao = FakeBillDao()
        val gemini = FakeGeminiValidator(GeminiResult.API_ERROR)
        val repo = BillRepository(dao, gemini, FakeGmailRepository(testEmail))

        repo.syncFromGmail()

        assertNull(
            "Bill must NOT be stored when Gemini returns api_error — " +
            "storing it as rejected would prevent future retries.",
            dao.getBillByEmailId(testEmail.bill.emailId)
        )
    }

    /**
     * REGRESSION: When Gemini returns api_error, the next sync must re-call Gemini.
     *
     * Old code: fails — dedup finds the rejected row and skips Gemini on second sync
     * Fixed code: passes — no DB row exists, so Gemini is called again
     */
    @Test
    fun `api_error from Gemini allows Gemini to be retried on next sync`() = runTest {
        val dao = FakeBillDao()
        val gemini = FakeGeminiValidator(GeminiResult.API_ERROR)
        val repo = BillRepository(dao, gemini, FakeGmailRepository(testEmail))

        repo.syncFromGmail() // first attempt — network failure
        repo.syncFromGmail() // second attempt — should retry, not skip

        assertEquals(
            "Gemini must be called on BOTH syncs. " +
            "If it's called only once, the bug is present: the first sync stored the bill " +
            "as rejected, and the second sync's dedup check blocked re-evaluation.",
            2,
            gemini.callCount
        )
    }

    /**
     * Sanity check: parse_error (garbled API response) is also not stored, for the same reason.
     */
    @Test
    fun `parse_error from Gemini does not permanently reject the bill`() = runTest {
        val dao = FakeBillDao()
        val gemini = FakeGeminiValidator(GeminiResult.PARSE_ERROR)
        val repo = BillRepository(dao, gemini, FakeGmailRepository(testEmail))

        repo.syncFromGmail()

        assertNull(
            "Bill must not be stored when Gemini returns parse_error.",
            dao.getBillByEmailId(testEmail.bill.emailId)
        )
    }

    /**
     * Sanity check: a successful high-confidence invoice IS stored as PENDING.
     */
    @Test
    fun `successful invoice from Gemini is stored as PENDING`() = runTest {
        val dao = FakeBillDao()
        val invoiceResult = GeminiResult(
            isInvoice   = true,
            isReceipt   = false,
            isDuplicate = false,
            provider    = "Unknown Bank",
            amount      = 250.00,
            dueDate     = null,
            confidence  = 0.95,
            reason      = "Identified as a credit card statement requiring payment."
        )
        val gemini = FakeGeminiValidator(invoiceResult)
        val repo = BillRepository(dao, gemini, FakeGmailRepository(testEmail))

        repo.syncFromGmail()

        val stored = dao.getBillByEmailId(testEmail.bill.emailId)
        assertNotNull("High-confidence invoice must be stored.", stored)
        assertEquals(BillStatus.PENDING, stored!!.status)
    }

    // ── Fakes ──────────────────────────────────────────────────────────────────

    /**
     * Fake GeminiValidator that returns a fixed [result] and counts how many times
     * [validateAndExtract] is called — used to verify retry behaviour.
     */
    private class FakeGeminiValidator(
        private val result: GeminiResult
    ) : GeminiValidator(FakeGeminiCacheDao()) {
        var callCount = 0
        override suspend fun validateAndExtract(
            emailId: String,
            subject: String,
            snippet: String
        ): GeminiResult {
            callCount++
            return result
        }
    }

    /**
     * No-op GeminiCacheDao — satisfies the GeminiValidator constructor in tests.
     * The parent's cache logic is never reached because [FakeGeminiValidator]
     * overrides [validateAndExtract] entirely.
     */
    private class FakeGeminiCacheDao : GeminiCacheDao {
        override suspend fun getByEmailId(emailId: String): GeminiCache? = null
        override suspend fun insert(cache: GeminiCache) = Unit
        override suspend fun deleteOlderThan(cutoff: Long) = Unit
        override suspend fun clearAll() = Unit
    }

    /**
     * Fake GmailRepository that always returns [email].
     * The real [GmailRepository] requires a signed-in Google account — we bypass
     * that entirely by overriding [fetchBillEmails].
     *
     * [mock] supplies a non-null Context so Kotlin's null-check on the constructor
     * parameter passes; the context is never used since [fetchBillEmails] is overridden.
     */
    private class FakeGmailRepository(
        private val email: ParsedEmailResult
    ) : GmailRepository(mock()) {
        override suspend fun fetchBillEmails(): Result<List<ParsedEmailResult>> =
            Result.success(listOf(email))
    }

    /**
     * In-memory BillDao that stores bills in a plain map.
     * Only the methods exercised by [BillRepository.syncFromGmail] are implemented.
     */
    private class FakeBillDao : BillDao {
        private val bills = mutableMapOf<String, Bill>()

        override suspend fun getBillByEmailId(emailId: String): Bill? = bills[emailId]

        override suspend fun insertBill(bill: Bill): Long {
            bills[bill.emailId] = bill
            return bill.id
        }

        // ── Remaining interface methods — not used by syncFromGmail ───────────

        override fun getAllVisibleBills(retentionLimit: Long): LiveData<List<Bill>> =
            MutableLiveData(emptyList())

        override suspend fun getBillById(id: Long): Bill? =
            bills.values.firstOrNull { it.id == id }

        override suspend fun insertBills(bills: List<Bill>) =
            bills.forEach { insertBill(it) }

        override suspend fun updateBill(bill: Bill) { this.bills[bill.emailId] = bill }
        override suspend fun deleteBill(bill: Bill) { bills.remove(bill.emailId) }
        override suspend fun updateBillStatus(id: Long, status: BillStatus) {}
        override suspend fun updateCalendarEventId(id: Long, eventId: String) {}
        override suspend fun updateReminderSet(id: Long, set: Boolean) {}
        override suspend fun deleteBillByEmailId(emailId: String) { bills.remove(emailId) }
        override suspend fun deleteAllBills() { bills.clear() }
        override suspend fun confirmBill(id: Long) {}
        override suspend fun dismissBill(id: Long) {}
    }
}
