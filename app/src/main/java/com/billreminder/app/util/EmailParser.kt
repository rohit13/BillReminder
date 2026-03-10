package com.billreminder.app.util

import android.util.Log
import com.billreminder.app.model.Bill
import com.billreminder.app.model.BillStatus
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

object EmailParser {

    private const val TAG = "EmailParser"

    // Keywords that indicate a bill email
    private val BILL_KEYWORDS = listOf(
        "bill", "invoice", "payment due", "amount due", "balance due",
        "statement", "your bill", "due date", "minimum payment",
        "autopay", "account statement", "electricity bill", "water bill",
        "internet bill", "phone bill", "gas bill", "credit card statement",
        "utility bill", "subscription", "renewal", "outstanding balance",
        "pay now", "payment reminder", "overdue", "past due"
    )

    // Category detection
    private val CATEGORY_PATTERNS = mapOf(
        "Electricity" to listOf("electric", "power", "energy", "pge", "con ed", "duke energy", "utility"),
        "Water" to listOf("water", "sewer", "wastewater"),
        "Internet" to listOf("internet", "broadband", "wifi", "comcast", "xfinity", "att", "verizon fios", "spectrum"),
        "Phone" to listOf("phone", "mobile", "wireless", "t-mobile", "verizon", "at&t", "sprint"),
        "Gas" to listOf("gas", "natural gas", "heating"),
        "Credit Card" to listOf("credit card", "visa", "mastercard", "amex", "discover", "chase", "citi"),
        "Insurance" to listOf("insurance", "premium", "geico", "allstate", "progressive", "state farm"),
        "Streaming" to listOf("netflix", "hulu", "spotify", "disney", "amazon prime", "youtube premium"),
        "Rent" to listOf("rent", "lease", "landlord", "property management"),
        "Mortgage" to listOf("mortgage", "loan payment", "home loan"),
        "Medical" to listOf("medical", "hospital", "clinic", "doctor", "dental", "pharmacy")
    )

    // Amount patterns
    private val AMOUNT_PATTERNS = listOf(
        Pattern.compile("""(?:amount due|balance due|total due|payment due|minimum payment)[:\s]*[$€£¥]?\s*([\d,]+\.?\d*)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""[$€£¥]\s*([\d,]+\.\d{2})"""),
        Pattern.compile("""([\d,]+\.\d{2})\s*(?:USD|EUR|GBP)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""(?:pay|due)[:\s]*[$€£¥]?\s*([\d,]+\.?\d*)""", Pattern.CASE_INSENSITIVE)
    )

    // Date patterns
    private val DATE_FORMATS = listOf(
        SimpleDateFormat("MM/dd/yyyy", Locale.US),
        SimpleDateFormat("MM-dd-yyyy", Locale.US),
        SimpleDateFormat("MMMM d, yyyy", Locale.US),
        SimpleDateFormat("MMM d, yyyy", Locale.US),
        SimpleDateFormat("d MMMM yyyy", Locale.US),
        SimpleDateFormat("yyyy-MM-dd", Locale.US),
        SimpleDateFormat("MM/dd/yy", Locale.US),
        SimpleDateFormat("MMMM dd, yyyy", Locale.US)
    )

    private val DUE_DATE_PATTERNS = listOf(
        Pattern.compile("""(?:due|payment due|due date|pay by|due on)[:\s]+(\w+\s+\d{1,2},?\s+\d{4})""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""(?:due|payment due|due date|pay by|due on)[:\s]+(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})\s+(?:is your due date|payment due)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""due[:\s]+([A-Za-z]+\s+\d{1,2},?\s+\d{4})""", Pattern.CASE_INSENSITIVE)
    )

    fun isBillEmail(subject: String, body: String, sender: String): Boolean {
        val textToCheck = "$subject $body $sender".lowercase()
        return BILL_KEYWORDS.any { keyword -> textToCheck.contains(keyword) }
    }

    fun parseBillFromEmail(
        emailId: String,
        subject: String,
        body: String,
        senderName: String,
        senderEmail: String,
        receivedTimestamp: Long
    ): Bill? {
        if (!isBillEmail(subject, body, senderEmail)) return null

        val amount = extractAmount(body) ?: extractAmount(subject) ?: 0.0
        val dueDate = extractDueDate(body) ?: extractDueDate(subject) ?: estimateDueDate(receivedTimestamp)
        val category = detectCategory(subject, body, senderEmail)

        return Bill(
            emailId = emailId,
            subject = subject,
            sender = senderName,
            senderEmail = senderEmail,
            amount = amount,
            currency = "USD",
            dueDate = dueDate,
            receivedDate = receivedTimestamp,
            description = extractDescription(subject, senderName),
            category = category,
            status = BillStatus.PENDING,
            rawEmailSnippet = body.take(500)
        )
    }

    private fun extractAmount(text: String): Double? {
        for (pattern in AMOUNT_PATTERNS) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                try {
                    val amountStr = matcher.group(1)?.replace(",", "") ?: continue
                    val amount = amountStr.toDouble()
                    if (amount > 0 && amount < 100000) return amount
                } catch (e: NumberFormatException) {
                    continue
                }
            }
        }
        return null
    }

    private fun extractDueDate(text: String): Long? {
        for (pattern in DUE_DATE_PATTERNS) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val dateStr = matcher.group(1) ?: continue
                val date = parseDate(dateStr)
                if (date != null) return date
            }
        }
        return null
    }

    private fun parseDate(dateStr: String): Long? {
        for (format in DATE_FORMATS) {
            try {
                val date = format.parse(dateStr.trim())
                if (date != null) {
                    val cal = Calendar.getInstance()
                    cal.time = date
                    // If year is missing or seems off, use current year
                    if (cal.get(Calendar.YEAR) < 2020) {
                        cal.set(Calendar.YEAR, Calendar.getInstance().get(Calendar.YEAR))
                    }
                    return cal.timeInMillis
                }
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    private fun estimateDueDate(receivedTimestamp: Long): Long {
        // Default: due in 30 days
        return receivedTimestamp + (30L * 24 * 60 * 60 * 1000)
    }

    private fun detectCategory(subject: String, body: String, senderEmail: String): String {
        val text = "$subject $body $senderEmail".lowercase()
        for ((category, keywords) in CATEGORY_PATTERNS) {
            if (keywords.any { text.contains(it) }) return category
        }
        return "Other"
    }

    private fun extractDescription(subject: String, senderName: String): String {
        return when {
            subject.isNotBlank() -> subject
            senderName.isNotBlank() -> "Bill from $senderName"
            else -> "Bill"
        }
    }
}
