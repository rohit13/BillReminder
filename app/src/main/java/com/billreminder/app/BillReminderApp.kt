package com.billreminder.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class BillReminderApp : Application() {

    companion object {
        const val CHANNEL_ID_BILLS = "bill_reminders"
        const val CHANNEL_ID_SYNC = "sync_notifications"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val billChannel = NotificationChannel(
                CHANNEL_ID_BILLS,
                "Bill Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for upcoming bill due dates"
                enableVibration(true)
            }

            val syncChannel = NotificationChannel(
                CHANNEL_ID_SYNC,
                "Email Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background email sync status"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(billChannel)
            manager.createNotificationChannel(syncChannel)
        }
    }
}
