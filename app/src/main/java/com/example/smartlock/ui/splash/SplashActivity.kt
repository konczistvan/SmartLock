package com.example.smartlock.ui.splash

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.example.smartlock.R
import com.example.smartlock.api.FirebaseClient
import com.example.smartlock.ui.login.LoginActivity
import com.example.smartlock.ui.main.MainActivity
import com.example.smartlock.ui.onboarding.OnboardingActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        Handler(Looper.getMainLooper()).postDelayed({
            val prefs         = getSharedPreferences("smartlock_prefs", MODE_PRIVATE)
            val onboardingDone = prefs.getBoolean("onboarding_done", false)
            val loggedIn       = FirebaseClient.auth.currentUser != null

            val intent = when {
                !onboardingDone -> Intent(this, OnboardingActivity::class.java)
                loggedIn        -> Intent(this, MainActivity::class.java)
                else            -> Intent(this, LoginActivity::class.java)
            }
            startActivity(intent.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }, 1800)
    }
}