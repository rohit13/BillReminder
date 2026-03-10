package com.billreminder.app.auth

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.gmail.GmailScopes

object AuthManager {
    private const val TAG = "AuthManager"

    // Build the Google Sign-In client requesting Gmail + Calendar scopes
    fun getGoogleSignInClient(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(
                Scope(GmailScopes.GMAIL_READONLY),
                Scope(CalendarScopes.CALENDAR)
            )
            .build()

        return GoogleSignIn.getClient(context, gso)
    }

    fun getSignInIntent(context: Context): Intent {
        return getGoogleSignInClient(context).signInIntent
    }

    fun getLastSignedInAccount(context: Context): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    fun isSignedIn(context: Context): Boolean {
        val account = getLastSignedInAccount(context) ?: return false
        // Check if scopes are granted
        return GoogleSignIn.hasPermissions(
            account,
            Scope(GmailScopes.GMAIL_READONLY),
            Scope(CalendarScopes.CALENDAR)
        )
    }

    fun signOut(context: Context, onComplete: () -> Unit) {
        getGoogleSignInClient(context).signOut().addOnCompleteListener { onComplete() }
    }

    fun revokeAccess(context: Context, onComplete: () -> Unit) {
        getGoogleSignInClient(context).revokeAccess().addOnCompleteListener { onComplete() }
    }
}
