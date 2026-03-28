package com.billreminder.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted result of a Gemini API call for a given email.
 *
 * Both accepted AND rejected emails are cached here so that:
 *  - Accepted emails are not re-validated on subsequent syncs.
 *  - Rejected emails are also remembered, preventing repeated API calls for
 *    emails that Gemini already classified as non-invoices.
 *
 * [emailId] is the Gmail message ID and serves as the primary key.
 */
@Entity(tableName = "gemini_cache")
data class GeminiCache(
    @PrimaryKey
    val emailId: String,
    val isInvoice: Boolean,
    val isReceipt: Boolean,
    val isDuplicate: Boolean,
    val provider: String?,
    val amount: Double?,
    /** ISO-8601 date string "YYYY-MM-DD", or null if Gemini could not determine it. */
    val dueDate: String?,
    /** Gemini's confidence in its isInvoice classification, 0.0–1.0. */
    val confidence: Double,
    val reason: String?,
    /** [System.currentTimeMillis] at the time of the API call. */
    val checkedAt: Long
)
