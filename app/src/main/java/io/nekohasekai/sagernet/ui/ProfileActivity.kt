package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.GroupType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LvovFlow — Profile Screen
 * Accessible via bottom navigation "Профиль" tab.
 */
class ProfileActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        val prefs = getSharedPreferences("lvovflow", Context.MODE_PRIVATE)
        val email     = prefs.getString("user_email", "") ?: ""
        val expireDate = prefs.getString("expire_date", "") ?: ""

        // Populate header
        findViewById<TextView>(R.id.tv_email).text = email
        findViewById<TextView>(R.id.tv_expire).text =
            if (expireDate.isNotBlank()) "Подписка до $expireDate" else "Активная подписка"

        // Subscription sub-status row
        val tvSubStatus = findViewById<TextView>(R.id.tv_sub_status)
        tvSubStatus.text = if (expireDate.isNotBlank()) "Активна до $expireDate" else "Активна"

        // Version
        findViewById<TextView>(R.id.tv_version).text =
            "LvovFlow v${BuildConfig.VERSION_NAME}"

        // Back button
        findViewById<android.widget.ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        // ── Menu items ────────────────────────────────────────────────────────

        // Обновить подключение
        findViewById<LinearLayout>(R.id.item_refresh).setOnClickListener {
            refreshSubscription()
        }

        // Подписка — show info dialog
        findViewById<LinearLayout>(R.id.item_subscription).setOnClickListener {
            val msg = if (expireDate.isNotBlank())
                "Ваша подписка активна до $expireDate.\n\nДля продления свяжитесь с поддержкой или введите промокод."
            else
                "Ваша подписка активна."
            AlertDialog.Builder(this)
                .setTitle("⭐ Подписка")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        }

        // Поддержка — open Telegram bot
        findViewById<LinearLayout>(R.id.item_support).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/LvovFlowBot")))
            } catch (e: Exception) {
                Toast.makeText(this, "@LvovFlowBot", Toast.LENGTH_SHORT).show()
            }
        }

        // Промокод — show input dialog
        findViewById<LinearLayout>(R.id.item_promo).setOnClickListener {
            showPromoDialog()
        }

        // Выйти
        findViewById<LinearLayout>(R.id.item_logout).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Выйти из аккаунта?")
                .setMessage("Вы будете перенаправлены на экран входа.")
                .setPositiveButton("Выйти") { _, _ -> logout() }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    // ── Refresh subscription ──────────────────────────────────────────────────

    private fun refreshSubscription() {
        Toast.makeText(this, "Обновление серверов…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val groups = withContext(Dispatchers.IO) {
                    SagerDatabase.groupDao.allGroups()
                }
                val sub = groups.firstOrNull { it.type == GroupType.SUBSCRIPTION } ?: run {
                    Toast.makeText(this@ProfileActivity, "Подписка не найдена", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    GroupManager.updateGroup(sub)
                }
                Toast.makeText(this@ProfileActivity, "✅ Серверы обновлены", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@ProfileActivity, "Ошибка обновления", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Promo dialog ─────────────────────────────────────────────────────────

    private fun showPromoDialog() {
        val input = EditText(this).apply {
            hint = "Введите промокод"
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("🎟 Промокод")
            .setView(input)
            .setPositiveButton("Применить") { _, _ ->
                val code = input.text.toString().trim().uppercase()
                applyPromo(code)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun applyPromo(code: String) {
        if (code.isBlank()) return
        // TODO: wire to your apply_promo.php API endpoint
        Toast.makeText(this, "Промокод «$code» принят! Свяжитесь с поддержкой для активации.", Toast.LENGTH_LONG).show()
    }

    // ── Logout ───────────────────────────────────────────────────────────────

    private fun logout() {
        getSharedPreferences("lvovflow", Context.MODE_PRIVATE).edit().clear().apply()
        startActivity(Intent(this, ActivationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
