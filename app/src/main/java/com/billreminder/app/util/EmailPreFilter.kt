package com.billreminder.app.util

import com.google.api.services.gmail.model.MessagePartHeader

/**
 * Stage 1 of the bill identification pipeline.
 *
 * Applies fast, zero-cost local rules to triage incoming emails before any
 * Gemini API call is made:
 *
 *  - AutoAccept : known billing domain + no List-Unsubscribe header → skip Gemini
 *  - AutoReject : post-payment subject phrase → discard immediately
 *  - NeedsGemini: everything else → proceed to Stage 2
 */
sealed class PreFilterResult {
    object AutoAccept : PreFilterResult()
    object AutoReject : PreFilterResult()
    object NeedsGemini : PreFilterResult()
}

object EmailPreFilter {

    /**
     * Known billing-only sender domains. Emails whose senderEmail ends with one
     * of these domains (and carries no List-Unsubscribe header) are treated as
     * confirmed invoices without a Gemini call.
     *
     * Only add domains that exclusively send payment-due notices — never
     * marketing domains shared with promotional mail.
     */
    private val BILLING_DOMAINS = setOf(
        "billing.att.com",
        "noreply.netflix.com",
        "billing.amazon.com",
        "billing.paypal.com",
        "payments.paypal.com",
        "noreply.hulu.com",
        "email.comcast.net",
        "billing.xfinity.com",
        "account.verizon.com",
        "billing.t-mobile.com",
        "bills.spectrum.com",
        "ebill.duke-energy.com",
        "bills.pge.com",
        "ebill.coned.com",
        "noreply.chase.com",
        "alerts.chase.com",
        "accountservices.citi.com",
        "billing.google.com",
        "billing.apple.com",
        "invoices.stripe.com",
        "billing.spotify.com",
        "billing.dropbox.com",
        "billing.microsoft.com"
    )

    /**
     * Subject phrases that indicate payment has already been made.
     * Presence of any phrase (case-insensitive) → AutoReject.
     */
    private val POST_PAYMENT_PHRASES = listOf(
        "thank you for your payment",
        "payment received",
        "payment confirmation",
        "order confirmed",
        "receipt for your payment",
        "your payment of",
        "payment processed",
        "payment successful",
        "transaction successful",
        "your order has shipped",
        "your order is on its way"
    )

    /**
     * Evaluate a single email and return a triage decision.
     *
     * @param senderEmail  The extracted sender email address (lowercased externally is fine,
     *                     but this function lowercases it internally for safety).
     * @param subject      The email subject line.
     * @param headers      Full list of MIME headers from the Gmail message payload.
     */
    fun evaluate(
        senderEmail: String,
        subject: String,
        headers: List<MessagePartHeader>
    ): PreFilterResult {
        val emailLower = senderEmail.lowercase()
        val subjectLower = subject.lowercase()

        // Rule 1: Post-payment rejection (highest priority — check before allowlist)
        if (POST_PAYMENT_PHRASES.any { subjectLower.contains(it) }) {
            return PreFilterResult.AutoReject
        }

        // Rule 2: Known billing domain allowlist
        val isDomainAllowed = BILLING_DOMAINS.any { domain ->
            emailLower.endsWith("@$domain") || emailLower == domain
        }

        if (isDomainAllowed) {
            // Soft signal: even trusted senders occasionally add List-Unsubscribe to
            // account summary or promotional emails. Downgrade to NeedsGemini in that case.
            val hasUnsubscribeHeader = headers.any {
                it.name.equals("List-Unsubscribe", ignoreCase = true)
            }
            return if (hasUnsubscribeHeader) PreFilterResult.NeedsGemini
            else PreFilterResult.AutoAccept
        }

        // Rule 3: Unknown sender — always validate with Gemini
        return PreFilterResult.NeedsGemini
    }
}
