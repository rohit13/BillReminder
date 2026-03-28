package com.billreminder.app.repository

import com.billreminder.app.model.Bill
import com.google.api.services.gmail.model.MessagePartHeader

/**
 * Carries a parsed [Bill] together with the raw MIME headers from the Gmail
 * message. The headers are needed by [com.billreminder.app.util.EmailPreFilter]
 * to inspect List-Unsubscribe and other header-level signals without re-fetching
 * the message from the API.
 */
data class ParsedEmailResult(
    val bill: Bill,
    val headers: List<MessagePartHeader>
)
