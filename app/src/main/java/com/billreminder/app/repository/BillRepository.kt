package com.billreminder.app.repository

import android.content.Context
import com.billreminder.app.data.BillDatabase
import com.billreminder.app.model.Bill
import com.billreminder.app.model.BillStatus

class BillRepository(context: Context) {
    private val dao = BillDatabase.getInstance(context).billDao()
    private val gmailRepo = GmailRepository(context)
    private val calendarRepo = CalendarRepository(context)

    val allBills = dao.getAllBills()
    val activeBills = dao.getActiveBills()

    suspend fun syncFromGmail(): Result<Int> {
        val result = gmailRepo.fetchBillEmails()
        return if (result.isSuccess) {
            val bills = result.getOrNull() ?: emptyList()
            var newCount = 0
            for (bill in bills) {
                val existing = dao.getBillByEmailId(bill.emailId)
                if (existing == null) {
                    dao.insertBill(bill)
                    newCount++
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
