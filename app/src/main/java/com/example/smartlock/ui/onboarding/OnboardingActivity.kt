package com.example.smartlock.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.smartlock.R
import com.example.smartlock.ui.login.LoginActivity
import com.example.smartlock.ui.register.RegisterActivity

class OnboardingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        findViewById<android.widget.Button>(R.id.btnGetStarted).setOnClickListener {
            markDone()
            startActivity(Intent(this, RegisterActivity::class.java).clearStack())
        }

        findViewById<TextView>(R.id.tvAlreadyHaveAccount).setOnClickListener {
            markDone()
            startActivity(Intent(this, LoginActivity::class.java).clearStack())
        }
    }

    private fun markDone() {
        getSharedPreferences("smartlock_prefs", MODE_PRIVATE)
            .edit().putBoolean("onboarding_done", true).apply()
    }

    private fun Intent.clearStack() = apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
}
