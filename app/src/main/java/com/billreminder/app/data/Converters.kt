package com.billreminder.app.data

import androidx.room.TypeConverter
import com.billreminder.app.model.BillStatus

class Converters {
    @TypeConverter
    fun fromBillStatus(status: BillStatus): String = status.name

    @TypeConverter
    fun toBillStatus(status: String): BillStatus = BillStatus.valueOf(status)
}
