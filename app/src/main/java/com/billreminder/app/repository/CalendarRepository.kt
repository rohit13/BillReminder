package com.billreminder.app.repository

import android.content.Context
import android.util.Log
import com.billreminder.app.model.Bill
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventDateTime
import com.google.api.services.calendar.model.EventReminder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone

class CalendarRepository(private val context: Context) {

    companion object {
        private const val TAG = "CalendarRepository"
        private const val APP_NAME = "BillReminder"
        private const val CALENDAR_ID = "primary"
    }

    private fun buildCalendarService(): Calendar? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null

        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(CalendarScopes.CALENDAR)
        ).apply {
            selectedAccount = account.account
        }

        return Calendar.Builder(
            AndroidHttp.newCompatibleTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName(APP_NAME)
            .build()
    }

    suspend fun addBillToCalendar(bill: Bill): Result<String> = withContext(Dispatchers.IO) {
        try {
            val calendar = buildCalendarService() ?: return@withContext Result.failure(
                Exception("Not signed in")
            )

            val dueDate = bill.getDueDateAsDate()
            val timeZone = TimeZone.getDefault()

            // Create all-day event on due date
            val startDt = EventDateTime().apply {
                date = DateTime(true, dueDate.time, timeZone.rawOffset / 60000)
                setTimeZone(timeZone.id)
            }

            val endDt = EventDateTime().apply {
                val endCal = java.util.Calendar.getInstance()
                endCal.time = dueDate
                endCal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                date = DateTime(true, endCal.timeInMillis, timeZone.rawOffset / 60000)
                setTimeZone(timeZone.id)
            }

            val amountStr = if (bill.amount > 0) " - \$%.2f".format(bill.amount) else ""
            val title = "💰 Bill Due: ${bill.description}$amountStr"
            val description = buildString {
                appendLine("Bill from: ${bill.sender}")
                if (bill.senderEmail.isNotBlank()) appendLine("Email: ${bill.senderEmail}")
                if (bill.amount > 0) appendLine("Amount: ${bill.currency} ${"%.2f".format(bill.amount)}")
                appendLine("Category: ${bill.category}")
                appendLine("\nAdded by Bill Reminder App")
            }

            // Set reminders: 1 day before and on the day
            val reminders = Event.Reminders().apply {
                useDefault = false
                overrides = listOf(
                    EventReminder().apply {
                        method = "popup"
                        minutes = 24 * 60 // 1 day before
                    },
                    EventReminder().apply {
                        method = "email"
                        minutes = 24 * 60
                    },
                    EventReminder().apply {
                        method = "popup"
                        minutes = 60 // 1 hour before
                    }
                )
            }

            val event = Event().apply {
                summary = title
                this.description = description
                start = startDt
                end = endDt
                this.reminders = reminders
                colorId = getColorIdForCategory(bill.category)
            }

            val createdEvent = calendar.events()
                .insert(CALENDAR_ID, event)
                .execute()

            Log.d(TAG, "Created calendar event: ${createdEvent.id}")
            Result.success(createdEvent.id)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding to calendar", e)
            Result.failure(e)
        }
    }

    suspend fun deleteCalendarEvent(eventId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val calendar = buildCalendarService() ?: return@withContext Result.failure(
                Exception("Not signed in")
            )
            calendar.events().delete(CALENDAR_ID, eventId).execute()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting calendar event", e)
            Result.failure(e)
        }
    }

    private fun getColorIdForCategory(category: String): String {
        return when (category) {
            "Electricity", "Gas", "Water" -> "11" // Red
            "Internet", "Phone" -> "9"             // Blueberry
            "Credit Card" -> "5"                   // Banana
            "Insurance" -> "6"                     // Sage
            "Streaming" -> "3"                     // Grape
            "Rent", "Mortgage" -> "4"              // Flamingo
            "Medical" -> "7"                       // Peacock
            else -> "1"                            // Lavender
        }
    }
}
