package com.billreminder.app.data

import androidx.lifecycle.LiveData
import androidx.room.*
import com.billreminder.app.model.Bill
import com.billreminder.app.model.BillStatus

@Dao
interface BillDao {
    @Query("""
        SELECT * FROM bills 
        WHERE ((status = 'PAID') OR (dueDate >= :retentionLimit)) 
        AND isRejectedByGemini = 0
        ORDER BY dueDate ASC
    """)
    fun getAllVisibleBills(retentionLimit: Long): LiveData<List<Bill>>

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun getBillById(id: Long): Bill?

    @Query("SELECT * FROM bills WHERE emailId = :emailId LIMIT 1")
    suspend fun getBillByEmailId(emailId: String): Bill?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBill(bill: Bill): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBills(bills: List<Bill>)

    @Update
    suspend fun updateBill(bill: Bill)

    @Delete
    suspend fun deleteBill(bill: Bill)

    @Query("UPDATE bills SET status = :status WHERE id = :id")
    suspend fun updateBillStatus(id: Long, status: BillStatus)

    @Query("UPDATE bills SET calendarEventId = :eventId WHERE id = :id")
    suspend fun updateCalendarEventId(id: Long, eventId: String)

    @Query("UPDATE bills SET reminderSet = :set WHERE id = :id")
    suspend fun updateReminderSet(id: Long, set: Boolean)

    @Query("DELETE FROM bills WHERE emailId = :emailId")
    suspend fun deleteBillByEmailId(emailId: String)
}
