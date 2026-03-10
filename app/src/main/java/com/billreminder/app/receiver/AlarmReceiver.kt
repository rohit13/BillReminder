package com.billreminder.app.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.billreminder.app.BillReminderApp
import com.billreminder.app.R
import com.billreminder.app.ui.MainActivity

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_BILL_ID = "bill_id"
        const val EXTRA_BILL_TITLE = "bill_title"
        const val EXTRA_BILL_AMOUNT = "bill_amount"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val billId = intent.getLongExtra(EXTRA_BILL_ID, -1)
        val billTitle = intent.getStringExtra(EXTRA_BILL_TITLE) ?: "Bill Due"
        val billAmount = intent.getDoubleExtra(EXTRA_BILL_AMOUNT, 0.0)

        showNotification(context, billId, billTitle, billAmount)
    }

    private fun showNotification(context: Context, billId: Long, title: String, amount: Double) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_bill_id", billId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, billId.toInt(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val amountStr = if (amount > 0) " (\$%.2f)".format(amount) else ""
        val notification = NotificationCompat.Builder(context, BillReminderApp.CHANNEL_ID_BILLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("💰 Bill Due: $title")
            .setContentText("Your bill$amountStr is due today!")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Your bill '$title'$amountStr is due today. Tap to view details."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(billId.toInt(), notification)
    }
}
