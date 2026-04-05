package com.billreminder.app.repository

import android.content.Context
import android.util.Base64
import android.util.Log
import com.billreminder.app.model.Bill
import com.billreminder.app.util.EmailParser
import com.google.api.services.gmail.model.MessagePartHeader
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.GmailScopes
import com.google.api.services.gmail.model.Message
import com.google.api.services.gmail.model.MessagePart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

open class GmailRepository(private val context: Context) {

    companion object {
        private const val TAG = "GmailRepository"
        private const val APP_NAME = "BillReminder"
    }

    private fun buildGmailService(): Gmail? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null

        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(GmailScopes.GMAIL_READONLY)
        ).apply {
            selectedAccount = account.account
        }

        return Gmail.Builder(
            AndroidHttp.newCompatibleTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName(APP_NAME)
            .build()
    }

    /**
     * Fetches bill-candidate emails from Gmail and returns them as [ParsedEmailResult]
     * objects, each containing the parsed [Bill] and the raw MIME headers needed by
     * [com.billreminder.app.util.EmailPreFilter] (Stage 1).
     */
    open suspend fun fetchBillEmails(): Result<List<ParsedEmailResult>> = withContext(Dispatchers.IO) {
        try {
            val gmail = buildGmailService() ?: return@withContext Result.failure(
                Exception("Not signed in")
            )

            val results = mutableListOf<ParsedEmailResult>()
            val userId = "me"

            // Search for bill-related emails
            val queries = listOf(
                "subject:(bill OR invoice OR \"payment due\" OR statement OR \"due date\")",
                "subject:(electricity OR water OR internet OR phone OR gas OR \"credit card\")",
                "\"amount due\" OR \"balance due\" OR \"minimum payment\" OR \"payment reminder\""
            )

            val processedIds = mutableSetOf<String>()

            for (query in queries) {
                try {
                    val listResponse = gmail.users().messages()
                        .list(userId)
                        .setQ(query)
                        .setMaxResults(50L)
                        .execute()

                    val messages = listResponse.messages ?: continue

                    for (msgRef in messages) {
                        if (processedIds.contains(msgRef.id)) continue
                        processedIds.add(msgRef.id)

                        try {
                            val message = gmail.users().messages()
                                .get(userId, msgRef.id)
                                .setFormat("full")
                                .execute()

                            val parsed = parseMessageToResult(message)
                            if (parsed != null) {
                                results.add(parsed)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error fetching message ${msgRef.id}", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error with query: $query", e)
                }
            }

            // Sort by due date
            results.sortBy { it.bill.dueDate }

            Result.success(results)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching emails", e)
            Result.failure(e)
        }
    }

    private fun parseMessageToResult(message: Message): ParsedEmailResult? {
        try {
            val headers: List<MessagePartHeader> = message.payload?.headers ?: return null

            val subject = headers.find { it.name == "Subject" }?.value ?: ""
            val from = headers.find { it.name == "From" }?.value ?: ""
            val dateStr = headers.find { it.name == "Date" }?.value
            val receivedDate = parseEmailDate(dateStr) ?: message.internalDate ?: System.currentTimeMillis()

            val (senderName, senderEmail) = parseSender(from)

            val body = extractBody(message)

            val bill = EmailParser.parseBillFromEmail(
                emailId = message.id ?: "",
                subject = subject,
                body = body,
                senderName = senderName,
                senderEmail = senderEmail,
                receivedTimestamp = receivedDate
            ) ?: return null

            return ParsedEmailResult(bill = bill, headers = headers)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message", e)
            return null
        }
    }

    private fun extractBody(message: Message): String {
        // Try to find HTML first for better preview, then fallback to plain text
        val htmlBody = findPart(message.payload, "text/html")
        if (htmlBody != null) return htmlBody

        val plainBody = findPart(message.payload, "text/plain")
        if (plainBody != null) return plainBody

        // Try direct body if no parts
        val bodyData = message.payload?.body?.data
        if (bodyData != null) {
            return decodeBase64(bodyData)
        }

        return message.snippet ?: ""
    }

    private fun findPart(part: MessagePart?, mimeType: String): String? {
        if (part == null) return null
        
        if (part.mimeType == mimeType) {
            val data = part.body?.data
            if (data != null) return decodeBase64(data)
        }

        if (part.parts != null) {
            for (subPart in part.parts) {
                val found = findPart(subPart, mimeType)
                if (found != null) return found
            }
        }
        
        return null
    }

    private fun decodeBase64(data: String): String {
        return try {
            String(Base64.decode(data, Base64.URL_SAFE), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseSender(from: String): Pair<String, String> {
        val emailRegex = Regex("<([^>]+)>")
        val emailMatch = emailRegex.find(from)
        val email = emailMatch?.groupValues?.get(1) ?: from.trim()
        val name = from.replace(emailRegex, "").trim().trim('"')
        return Pair(name.ifBlank { email }, email)
    }

    private fun parseEmailDate(dateStr: String?): Long? {
        if (dateStr == null) return null
        return try {
            val formats = listOf(
                java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", java.util.Locale.US),
                java.text.SimpleDateFormat("d MMM yyyy HH:mm:ss Z", java.util.Locale.US),
                java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z", java.util.Locale.US)
            )
            for (fmt in formats) {
                try {
                    return fmt.parse(dateStr)?.time
                } catch (e: Exception) { continue }
            }
            null
        } catch (e: Exception) { null }
    }
}
