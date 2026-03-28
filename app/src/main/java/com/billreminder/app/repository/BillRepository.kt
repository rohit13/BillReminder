package com.billreminder.app.repository

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import com.billreminder.app.data.BillDatabase
import com.billreminder.app.model.Bill
import com.billreminder.app.model.BillStatus
import com.billreminder.app.util.GeminiValidator

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
            val bills = result.getOrNull() ?: emptyList()
            var newCount = 0
            for (bill in bills) {
                val existing = dao.getBillByEmailId(bill.emailId)
                if (existing == null) {
                    // Gemini Verification: only insert if confirmed as a real bill
                    val isRealBill = GeminiValidator.validateIsInvoice(bill.subject, bill.rawEmailSnippet)
                    if (isRealBill) {
                        dao.insertBill(bill.copy(isGeminiVerified = true))
                        newCount++
                    } else {
                        Log.d(TAG, "Gemini rejected email, not inserting: ${bill.subject}")
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
