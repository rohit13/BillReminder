package com.billreminder.app.repository

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import com.billreminder.app.data.BillDatabase
import com.billreminder.app.model.Bill
import com.billreminder.app.model.BillStatus
import com.billreminder.app.util.EmailPreFilter
import com.billreminder.app.util.GeminiValidator
import com.billreminder.app.util.PreFilterResult

class BillRepository(context: Context) {
    private val dao = BillDatabase.getInstance(context).billDao()
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
                        // Trusted billing domain, no Gemini needed
                        Log.d(TAG, "Pre-filter AutoAccept: ${bill.subject}")
                        dao.insertBill(bill.copy(isRejectedByGemini = false))
                        newCount++
                    }

                    is PreFilterResult.AutoReject -> {
                        // Post-payment confirmation or other known non-invoice — discard
                        Log.d(TAG, "Pre-filter AutoReject: ${bill.subject}")
                    }

                    is PreFilterResult.NeedsGemini -> {
                        // Stage 2 (added in next branch): for now fall through to existing Gemini call
                        val isRealBill = GeminiValidator.validateIsInvoice(bill.subject, bill.rawEmailSnippet)
                        if (isRealBill) {
                            dao.insertBill(bill.copy(isGeminiVerified = true))
                            newCount++
                        } else {
                            Log.d(TAG, "Gemini rejected email: ${bill.subject}")
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

    suspend fun insertBill(bill: Bill) = dao.insertBill(bill)
    suspend fun updateBill(bill: Bill) = dao.updateBill(bill)
    suspend fun deleteBill(bill: Bill) = dao.deleteBill(bill)
    suspend fun getBillById(id: Long) = dao.getBillById(id)
    suspend fun setReminderSet(id: Long, set: Boolean) = dao.updateReminderSet(id, set)
}
