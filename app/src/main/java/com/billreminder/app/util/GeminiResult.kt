package com.billreminder.app.util

/**
 * In-memory result returned by [GeminiValidator.validateAndExtract].
 *
 * This is decoupled from [com.billreminder.app.data.GeminiCache] (the Room
 * entity) to keep the util layer free of database annotations.
 */
data class GeminiResult(
    val isInvoice: Boolean,
    val isReceipt: Boolean,
    val isDuplicate: Boolean,
    val provider: String?,
    /** Numeric amount owed (no currency symbol). Null if not found. */
    val amount: Double?,
    /** ISO-8601 date "YYYY-MM-DD". Null if not found. */
    val dueDate: String?,
    /** Gemini's confidence in the isInvoice classification, 0.0–1.0. */
    val confidence: Double,
    val reason: String?
) {
    companion object {
        /**
         * Returned on JSON parse failures — the API responded but the output was unusable.
         * confidence=0.0 ensures Stage 3 rejects it quietly.
         */
        val PARSE_ERROR = GeminiResult(
            isInvoice = false,
            isReceipt = false,
            isDuplicate = false,
            provider = null,
            amount = null,
            dueDate = null,
            confidence = 0.0,
            reason = "parse_error"
        )

        /**
         * Returned on network/API call failures — the request never completed.
         * confidence=0.0 ensures Stage 3 rejects it quietly.
         */
        val API_ERROR = GeminiResult(
            isInvoice = false,
            isReceipt = false,
            isDuplicate = false,
            provider = null,
            amount = null,
            dueDate = null,
            confidence = 0.0,
            reason = "api_error"
        )

        /** @deprecated Use [PARSE_ERROR] or [API_ERROR] instead. */
        val UNKNOWN get() = API_ERROR
    }
}
