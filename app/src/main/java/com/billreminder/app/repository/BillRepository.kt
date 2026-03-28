package com.billreminder.app.repository

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import com.billreminder.app.data.BillDatabase
import com.billreminder.app.model.Bill
import com.billreminder.app.model.BillStatus
import com.billreminder.app.util.EmailParser
import com.billreminder.app.util.EmailPreFilter
import com.billreminder.app.util.GeminiValidator
import com.billreminder.app.util.PreFilterResult

class BillRepository(context: Context) {
    private val db = BillDatabase.getInstance(context)
    private val dao = db.billDao()
    private val geminiValidator = GeminiValidator(db.geminiCacheDao())
    private val gmailRepo = GmailRepository(context)
    private val calendarRepo = CalendarRepository(context)

    companion object {
        private const val TAG = "BillRepository"
    }

    fun getVisibleBills(): LiveData<List<Bill>> {
        // Retention Policy: Exclude bills overdue for more than 15 days
        val fifteenDaysAgo = System.currentTimeMillis() - (15L * 24 * 60 * 60 * 1000)
        return dao.getAllVisibleBills(fifteenDaysAgo)
    }

    suspend fun syncFromGmail(): Result<Int> {
        val result = gmailRepo.fetchBillEmails()
        return if (result.isSuccess) {
            val emailResults = result.getOrNull() ?: emptyList()
            var newCount = 0

            for (parsed in emailResults) {
                val bill = parsed.bill

                // Dedup: skip emails already in the database
                if (dao.getBillByEmailId(bill.emailId) != null) continue

                // Stage 1: Pre-filter — fast local triage before any Gemini call
                when (EmailPreFilter.evaluate(bill.senderEmail, bill.subject, parsed.headers)) {

                    is PreFilterResult.AutoAccept -> {
                        // Trusted billing domain — no Gemini needed, confidence sentinel = 1.0
                        Log.d(TAG, "Pre-filter AutoAccept: ${bill.subject}")
                        dao.insertBill(bill.copy(isRejectedByGemini = false, geminiConfidence = 1.0))
                        newCount++
                    }

                    is PreFilterResult.AutoReject -> {
                        // Post-payment confirmation or other known non-invoice — discard
                        Log.d(TAG, "Pre-filter AutoReject: ${bill.subject}")
                    }

                    is PreFilterResult.NeedsGemini -> {
                        // Stage 2: enriched Gemini call with structured JSON + caching
                        val geminiResult = geminiValidator.validateAndExtract(
                            emailId = bill.emailId,
                            subject = bill.subject,
                            snippet = bill.rawEmailSnippet
                        )

                        // Receipts and duplicates are hard-rejected regardless of isInvoice
                        if (geminiResult.isReceipt || geminiResult.isDuplicate) {
                            Log.d(TAG, "Gemini: receipt/duplicate, skipping: ${bill.subject}")
                            continue
                        }

                        if (geminiResult.isInvoice) {
                            val mergedBill = EmailParser.mergeGeminiExtraction(bill, geminiResult)
                            // Stage 3: confidence thresholding
                            val insertStatus = when {
                                geminiResult.confidence >= 0.85 -> BillStatus.PENDING
                                geminiResult.confidence >= 0.60 -> BillStatus.NEEDS_REVIEW
                                else -> null // too uncertain — store as rejected for dedup
                            }
                            if (insertStatus != null) {
                                dao.insertBill(mergedBill.copy(status = insertStatus))
                                newCount++
                            } else {
                                // Confidence < 0.60: cached by GeminiCache, store row for dedup
                                Log.d(TAG, "Low confidence (${geminiResult.confidence}), rejected: ${bill.subject}")
                                dao.insertBill(mergedBill.copy(isRejectedByGemini = true))
                            }
                        } else {
                            Log.d(TAG, "Gemini non-invoice (confidence=${geminiResult.confidence}): ${bill.subject}")
                            // Store rejected row so dedup check works on next sync
                            dao.insertBill(bill.copy(isRejectedByGemini = true, geminiConfidence = geminiResult.confidence))
                        }
                    }
                }
            }
            Result.success(newCount)
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
        }
    }

    suspend fun addToCalendar(bill: Bill): Result<String> {
        val result = calendarRepo.addBillToCalendar(bill)
        if (result.isSuccess) {
            val eventId = result.getOrNull() ?: ""
            dao.updateCalendarEventId(bill.id, eventId)
        }
        return result
    }

    suspend fun markAsPaid(bill: Bill) {
        dao.updateBillStatus(bill.id, BillStatus.PAID)
        // Remove calendar event if set
        bill.calendarEventId?.let { eventId ->
            calendarRepo.deleteCalendarEvent(eventId)
        }
    }

    /** Promotes a NEEDS_REVIEW bill to PENDING after user confirmation. */
    suspend fun confirmBill(bill: Bill) = dao.confirmBill(bill.id)

    /** Dismisses a NEEDS_REVIEW bill — marks it rejected and removes calendar event if any. */
    suspend fun dismissBill(bill: Bill) {
        dao.dismissBill(bill.id)
        bill.calendarEventId?.let { calendarRepo.deleteCalendarEvent(it) }
    }

    suspend fun insertBill(bill: Bill) = dao.insertBill(bill)
    suspend fun updateBill(bill: Bill) = dao.updateBill(bill)
    suspend fun deleteBill(bill: Bill) = dao.deleteBill(bill)
    suspend fun getBillById(id: Long) = dao.getBillById(id)
    suspend fun setReminderSet(id: Long, set: Boolean) = dao.updateReminderSet(id, set)
}
