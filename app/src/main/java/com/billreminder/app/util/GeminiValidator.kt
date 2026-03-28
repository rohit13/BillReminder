package com.billreminder.app.util

import android.util.Log
import com.billreminder.app.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content

object GeminiValidator {
    private const val TAG = "GeminiValidator"
    
    private val generativeModel by lazy {
        GenerativeModel(
            modelName = "gemini-2.0-flash",
            apiKey = BuildConfig.GEMINI_API_KEY
        )
    }

    suspend fun validateIsInvoice(subject: String, snippet: String): Boolean {
        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            Log.e(TAG, "Gemini API Key is missing — skipping email")
            return false
        }

        val prompt = """
            You are a bill and invoice classifier. 
            Analyze the following email subject and snippet to determine if it's a real invoice or payment due notice that requires a financial transaction.
            
            Exclude:
            - Monthly statements with ${'$'}0 balance or no payment required.
            - "Thank you for your payment" or "Payment received" confirmations.
            - General marketing, newsletters, or policy updates.
            - Notices that don't specify an amount or a due date.
            
            Email Subject: ${subject}
            Email Snippet: ${snippet}
            
            Respond with exactly "YES" if it's a bill requiring payment, or "NO" if it's just informational, a confirmation, or marketing.
        """.trimIndent()

        return try {
            val response = generativeModel.generateContent(prompt)
            val result = response.text?.trim()?.uppercase()
            Log.d(TAG, "Gemini response for '$subject': $result")
            result == "YES"
        } catch (e: Exception) {
            Log.e(TAG, "Error calling Gemini API", e)
            false // On error, skip insertion — don't add unvalidated emails
        }
    }
}
