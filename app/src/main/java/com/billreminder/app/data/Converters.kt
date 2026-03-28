package com.billreminder.app.data

import androidx.room.TypeConverter
import com.billreminder.app.model.BillStatus

class Converters {
    @TypeConverter
    fun fromBillStatus(status: BillStatus?): String = status?.name ?: BillStatus.PENDING.name

    @TypeConverter
    fun toBillStatus(status: String?): BillStatus {
        if (status == null) return BillStatus.PENDING
        return try {
            BillStatus.valueOf(status)
        } catch (e: IllegalArgumentException) {
            BillStatus.PENDING
        }
    }
}
