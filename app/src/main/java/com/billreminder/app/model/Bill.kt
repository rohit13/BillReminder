package com.billreminder.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

enum class BillStatus {
    PENDING, DUE_SOON, OVERDUE, PAID
}

@Entity(tableName = "bills")
data class Bill(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val emailId: String = "",
    val subject: String = "",
    val sender: String = "",
    val senderEmail: String = "",
    val amount: Double = 0.0,
    val currency: String = "USD",
    val dueDate: Long = 0L,       // timestamp in millis
    val receivedDate: Long = 0L,
    val description: String = "",
    val category: String = "Other",
    val status: BillStatus = BillStatus.PENDING,
    val calendarEventId: String? = null,
    val reminderSet: Boolean = false,
    val rawEmailSnippet: String = ""
) {
    fun getDueDateAsDate(): Date = Date(dueDate)
    fun getReceivedDateAsDate(): Date = Date(receivedDate)

    fun isDueSoon(): Boolean {
        val now = System.currentTimeMillis()
        val threeDays = 3 * 24 * 60 * 60 * 1000L
        return dueDate > now && dueDate <= now + threeDays
    }

    fun isOverdue(): Boolean {
        return dueDate < System.currentTimeMillis() && status != BillStatus.PAID
    }

    fun getComputedStatus(): BillStatus {
        return when {
            status == BillStatus.PAID -> BillStatus.PAID
            isOverdue() -> BillStatus.OVERDUE
            isDueSoon() -> BillStatus.DUE_SOON
            else -> BillStatus.PENDING
        }
    }
}
