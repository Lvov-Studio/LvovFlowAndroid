package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sagernet.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * LvovFlow — Subscription Detail Screen
 * Shows current plan status, features included, and renew/upgrade options.
 */
class SubscriptionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Transparent nav/status bars
        window.statusBarColor = 0xFF0A1628.toInt()
        window.navigationBarColor = 0xFF0A1628.toInt()

        setContentView(R.layout.activity_subscription)
        supportActionBar?.hide()

        val prefs = getSharedPreferences("lvovflow", Context.MODE_PRIVATE)
        val email = prefs.getString("user_email", "") ?: ""
        val expireDate = prefs.getString("expire_date", "") ?: ""
        val sessionToken = prefs.getString("session_token", "") ?: ""

        // Back button
        findViewById<android.widget.ImageButton>(R.id.btn_back).setOnClickListener {
            finish()
        }

        // Plan name (currently all users are on "Flow")
        findViewById<TextView>(R.id.tv_plan_name).text = "Flow"

        // Expire date
        val tvExpire = findViewById<TextView>(R.id.tv_expire_date)
        val tvDaysLeft = findViewById<TextView>(R.id.tv_days_left)
        val tvStatusBadge = findViewById<TextView>(R.id.tv_status_badge)
        val dotStatus = findViewById<android.view.View>(R.id.dot_status)
        val badgeStatus = findViewById<LinearLayout>(R.id.badge_status)

        if (expireDate.isNotBlank()) {
            tvExpire.text = "Действует до $expireDate"

            // Calculate days remaining
            try {
                val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                val expDate = sdf.parse(expireDate)
                if (expDate != null) {
                    val diff = expDate.time - System.currentTimeMillis()
                    val daysLeft = TimeUnit.MILLISECONDS.toDays(diff).toInt()

                    if (daysLeft > 0) {
                        tvDaysLeft.text = daysLeft.toString()
                        tvStatusBadge.text = "Активна"
                        tvStatusBadge.setTextColor(0xFF22C55E.toInt())
                        dotStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF22C55E.toInt())
                        badgeStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(0x1A22C55E)
                    } else {
                        tvDaysLeft.text = "0"
                        tvStatusBadge.text = "Истекла"
                        tvStatusBadge.setTextColor(0xFFEF4444.toInt())
                        dotStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFEF4444.toInt())
                        badgeStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(0x1AEF4444)
                    }
                }
            } catch (_: Exception) {
                tvDaysLeft.text = "—"
            }
        } else {
            tvExpire.text = "Активная подписка"
            tvDaysLeft.text = "∞"
        }

        // Renew button → payment
        findViewById<Button>(R.id.btn_renew).setOnClickListener {
            val url = "https://lvovflow.com/api/payment/create.php?token=$sessionToken"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        // Upgrade button → switch to Tariffs tab
        findViewById<LinearLayout>(R.id.btn_upgrade).setOnClickListener {
            finish()
            // Navigate to tariffs tab in MainActivity
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("navigate_to_tab", "tariffs")
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }
    }
}
