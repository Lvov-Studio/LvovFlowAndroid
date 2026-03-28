package io.nekohasekai.sagernet.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val logo = findViewById<ImageView>(R.id.splash_logo)

        // Pulsing animation for the logo
        val scaleX = ObjectAnimator.ofFloat(logo, "scaleX", 1f, 1.15f, 1f).apply {
            duration = 1500L
            repeatCount = ObjectAnimator.INFINITE
        }
        val scaleY = ObjectAnimator.ofFloat(logo, "scaleY", 1f, 1.15f, 1f).apply {
            duration = 1500L
            repeatCount = ObjectAnimator.INFINITE
        }
        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            start()
        }

        lifecycleScope.launch {
            // Fake loading delay to show animation (in future, we can move the status.php check here)
            delay(1500L)

            // Check if user is logged in
            val prefs = getSharedPreferences("lvovflow", MODE_PRIVATE)
            val token = prefs.getString("session_token", "") ?: ""

            val nextIntent = if (token.isBlank()) {
                Intent(this@SplashActivity, ActivationActivity::class.java)
            } else {
                Intent(this@SplashActivity, MainActivity::class.java)
            }

            // Add smooth fade transition flags
            nextIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(nextIntent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
    }
}
