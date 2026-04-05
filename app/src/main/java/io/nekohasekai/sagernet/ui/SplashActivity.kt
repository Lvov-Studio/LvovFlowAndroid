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

        // ── Deep link: extract referral code from lvovflow.com/r/CODE ──
        val deepLinkRefCode = intent?.data?.let { uri ->
            if (uri.host == "lvovflow.com" && uri.pathSegments.size >= 2 && uri.pathSegments[0] == "r") {
                uri.pathSegments[1] // the referral code
            } else null
        }
        // Persist referral code so it survives app restart
        if (!deepLinkRefCode.isNullOrBlank()) {
            getSharedPreferences("lvovflow", MODE_PRIVATE).edit()
                .putString("pending_ref_code", deepLinkRefCode)
                .apply()
        }

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

            val isTv = TvUtils.isTv(this@SplashActivity)
            val nextIntent = if (token.isBlank()) {
                if (isTv) Intent(this@SplashActivity, TvActivationActivity::class.java)
                else Intent(this@SplashActivity, ActivationActivity::class.java)
            } else {
                if (isTv) Intent(this@SplashActivity, TvMainActivity::class.java)
                else Intent(this@SplashActivity, MainActivity::class.java)
            }

            // Forward referral code to ActivationActivity
            if (!deepLinkRefCode.isNullOrBlank()) {
                nextIntent.putExtra("ref_code", deepLinkRefCode)
            }

            // Add smooth fade transition flags
            nextIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(nextIntent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
    }
}
