package com.billreminder.app.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.billreminder.app.auth.AuthManager
import com.billreminder.app.databinding.ActivityLoginBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException

class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoginActivity"
        private const val RC_SIGN_IN = 9001
    }

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSignIn.setOnClickListener {
            signIn()
        }
    }

    private fun signIn() {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnSignIn.isEnabled = false
        val signInIntent = AuthManager.getSignInIntent(this)
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            binding.progressBar.visibility = View.GONE
            binding.btnSignIn.isEnabled = true
            handleSignInResult(data)
        }
    }

    private fun handleSignInResult(data: Intent?) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            Log.d(TAG, "Signed in: ${account.email}")
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } catch (e: ApiException) {
            Log.e(TAG, "Sign in failed: ${e.statusCode}", e)
            val msg = when (e.statusCode) {
                12501 -> "Sign-in cancelled"
                7 -> "Network error. Check your connection."
                10 -> "Developer error — check OAuth client configuration"
                else -> "Sign-in failed (code ${e.statusCode})"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }
}
