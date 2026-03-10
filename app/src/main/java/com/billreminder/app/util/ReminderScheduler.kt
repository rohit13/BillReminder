package com.billreminder.app.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.billreminder.app.model.Bill
import com.billreminder.app.receiver.AlarmReceiver
import java.util.Calendar

object ReminderScheduler {

    private const val TAG = "ReminderScheduler"

    fun scheduleReminder(context: Context, bill: Bill, daysBefore: Int = 1) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_BILL_ID, bill.id)
            putExtra(AlarmReceiver.EXTRA_BILL_TITLE, bill.description)
            putExtra(AlarmReceiver.EXTRA_BILL_AMOUNT, bill.amount)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context, bill.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Set alarm for `daysBefore` days before due date at 9 AM
        val cal = Calendar.getInstance().apply {
            timeInMillis = bill.dueDate
            add(Calendar.DAY_OF_MONTH, -daysBefore)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }

        if (cal.timeInMillis <= System.currentTimeMillis()) {
            // Due date already passed or alarm time in past — fire immediately (for testing)
            Log.d(TAG, "Bill ${bill.id} due date passed, skipping reminder")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    cal.timeInMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    cal.timeInMillis,
                    pendingIntent
                )
            }
            Log.d(TAG, "Scheduled reminder for bill ${bill.id} at ${cal.time}")
        } catch (e: SecurityException) {
            Log.e(TAG, "No permission to schedule exact alarm", e)
        }
    }

    fun cancelReminder(context: Context, billId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, billId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}
