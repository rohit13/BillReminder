package com.billreminder.app.data

import androidx.lifecycle.LiveData
import androidx.room.*
import com.billreminder.app.model.Bill
import com.billreminder.app.model.BillStatus

@Dao
interface BillDao {
    @Query("SELECT * FROM bills ORDER BY dueDate ASC")
    fun getAllBills(): LiveData<List<Bill>>

    @Query("SELECT * FROM bills ORDER BY dueDate ASC")
    suspend fun getAllBillsSync(): List<Bill>

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun getBillById(id: Long): Bill?

    @Query("SELECT * FROM bills WHERE emailId = :emailId LIMIT 1")
    suspend fun getBillByEmailId(emailId: String): Bill?

    @Query("SELECT * FROM bills WHERE status != 'PAID' ORDER BY dueDate ASC")
    fun getActiveBills(): LiveData<List<Bill>>

    @Query("SELECT * FROM bills WHERE dueDate BETWEEN :start AND :end ORDER BY dueDate ASC")
    suspend fun getBillsDueBetween(start: Long, end: Long): List<Bill>

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
