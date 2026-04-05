package io.nekohasekai.sagernet.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.group.GroupUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * LvovFlow — Activation Screen
 * Step 1: User enters email → OTP sent via Brevo
 * Step 2: User enters 6-digit OTP → gets subscription URL
 * Session is permanently stored in SharedPreferences (never expires until app uninstall)
 */
class ActivationActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "lvovflow"
        private const val KEY_TOKEN = "session_token"
        private const val KEY_SUB_URL = "subscription_url"
        private const val KEY_EMAIL = "user_email"
        private const val KEY_EXPIRY = "expire_date"
        private const val API_BASE = "https://lvovflow.com/api/app"

        /**
         * Check if user is already authenticated.
         * Returns true if a session token exists in SharedPreferences.
         */
        fun isAuthenticated(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_TOKEN, null)?.isNotBlank() == true
        }

        /**
         * Get the stored subscription URL.
         */
        fun getSubscriptionUrl(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_SUB_URL, null)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentEmail: String = ""
    private var pendingRefCode: String = ""

    // Views
    private lateinit var stepEmail: LinearLayout
    private lateinit var stepOtp: LinearLayout
    private lateinit var emailInput: EditText
    private lateinit var otpInput: EditText
    private lateinit var btnSendCode: Button
    private lateinit var btnVerify: Button
    private lateinit var btnBackToEmail: TextView
    private lateinit var btnResend: TextView
    private lateinit var otpHintEmail: TextView
    private lateinit var errorLabel: TextView
    private lateinit var emailProgressRow: LinearLayout
    private lateinit var verifyProgressRow: LinearLayout
    private lateinit var logoIcon: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Read referral code from deep link (Intent extra) or SharedPreferences ──
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        pendingRefCode = intent?.getStringExtra("ref_code")
            ?: prefs.getString("pending_ref_code", "") ?: ""

        // If user already has a token → go directly to MainActivity
        if (isAuthenticated(this)) {
            goToMain()
            return
        }

        setContentView(R.layout.layout_activation)

        stepEmail = findViewById(R.id.step_email)
        stepOtp = findViewById(R.id.step_otp)
        emailInput = findViewById(R.id.email_input)
        otpInput = findViewById(R.id.otp_input)
        btnSendCode = findViewById(R.id.btn_send_code)
        btnVerify = findViewById(R.id.btn_verify)
        btnBackToEmail = findViewById(R.id.btn_back_to_email)
        btnResend = findViewById(R.id.btn_resend)
        otpHintEmail = findViewById(R.id.otp_hint_email)
        errorLabel = findViewById(R.id.error_label)
        emailProgressRow = findViewById(R.id.email_progress_row)
        verifyProgressRow = findViewById(R.id.verify_progress_row)
        logoIcon = findViewById(R.id.logo_icon)

        // Animate logo on enter
        animateLogo()

        // Step 1: send OTP
        btnSendCode.setOnClickListener { sendCode() }
        emailInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { sendCode(); true } else false
        }

        // Step 2: verify OTP
        btnVerify.setOnClickListener { verifyCode() }
        otpInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { verifyCode(); true } else false
        }

        // Back to email
        btnBackToEmail.setOnClickListener { showEmailStep() }

        // Resend
        btnResend.setOnClickListener {
            if (currentEmail.isNotBlank()) sendCode(resend = true)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI transitions
    // ─────────────────────────────────────────────────────────────────────────

    private fun showEmailStep() {
        stepOtp.visibility = View.GONE
        stepEmail.visibility = View.VISIBLE
        hideError()
        emailInput.requestFocus()
    }

    private fun showOtpStep(email: String) {
        currentEmail = email
        otpHintEmail.text = "Код отправлен на $email"
        stepEmail.visibility = View.GONE
        stepOtp.visibility = View.VISIBLE
        hideError()
        otpInput.text?.clear()
        otpInput.requestFocus()
        showKeyboard(otpInput)
    }

    private fun showError(msg: String) {
        errorLabel.text = msg
        errorLabel.visibility = View.VISIBLE
    }

    private fun hideError() {
        errorLabel.visibility = View.GONE
    }

    private fun setEmailLoading(loading: Boolean) {
        btnSendCode.isEnabled = !loading
        emailProgressRow.visibility = if (loading) View.VISIBLE else View.GONE
        emailInput.isEnabled = !loading
    }

    private fun setVerifyLoading(loading: Boolean) {
        btnVerify.isEnabled = !loading
        verifyProgressRow.visibility = if (loading) View.VISIBLE else View.GONE
        otpInput.isEnabled = !loading
        btnResend.isEnabled = !loading
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Network calls
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendCode(resend: Boolean = false) {
        val email = emailInput.text.toString().trim().lowercase()
        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError("Введите корректный email адрес")
            return
        }
        hideError()
        hideKeyboard()
        setEmailLoading(true)

        scope.launch {
            try {
                val result = postJson("$API_BASE/send_otp.php", mapOf("email" to email))
                val ok = result.optBoolean("ok", false)
                if (ok) {
                    withContext(Dispatchers.Main) {
                        setEmailLoading(false)
                        showOtpStep(email)
                        if (resend) Toast.makeText(this@ActivationActivity, "Код отправлен повторно", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val errorMsg = result.optString("error", "Пользователь с таким email не найден")
                    withContext(Dispatchers.Main) {
                        setEmailLoading(false)
                        showError(errorMsg)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setEmailLoading(false)
                    showError("Ошибка сети. Проверьте подключение.")
                }
            }
        }
    }

    private fun verifyCode() {
        val code = otpInput.text.toString().trim()
        if (code.length != 6) {
            showError("Введите 6-значный код из письма")
            return
        }
        hideError()
        hideKeyboard()
        setVerifyLoading(true)

        scope.launch {
            try {
                val bodyMap = mutableMapOf("email" to currentEmail, "code" to code)
                if (pendingRefCode.isNotBlank()) {
                    bodyMap["ref_code"] = pendingRefCode
                }
                val result = postJson("$API_BASE/verify_otp.php", bodyMap)
                val ok = result.optBoolean("ok", false)
                if (ok) {
                    val token = result.optString("token", "")
                    val subUrl = result.optString("subscription_url", "")
                    val isExpired = result.optBoolean("is_expired", false)
                    val expireDate = result.optString("expire_date", "")
                    val status = result.optString("status", "active")

                    if (token.isBlank() || subUrl.isBlank()) {
                        withContext(Dispatchers.Main) {
                            setVerifyLoading(false)
                            showError("Ошибка сервера. Попробуйте снова.")
                        }
                        return@launch
                    }

                    // Сохраняем сессию в любом случае (чтобы не просить OTP повторно)
                    saveSession(token, subUrl, currentEmail, expireDate)

                    // Clear pending referral code after successful registration
                    if (pendingRefCode.isNotBlank()) {
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                            .remove("pending_ref_code").apply()
                        pendingRefCode = ""
                    }

                    if (isExpired) {
                        // Подписка истекла — показываем экран с предложением продлить
                        withContext(Dispatchers.Main) {
                            setVerifyLoading(false)
                            showExpiredScreen(expireDate)
                        }
                        return@launch
                    }

                    // Активная подписка — импортируем и переходим
                    importSubscription(subUrl)

                    withContext(Dispatchers.Main) {
                        setVerifyLoading(false)
                        goToMain()
                    }

                } else {
                    val errorMsg = result.optString("error", "Неверный или истёкший код")
                    withContext(Dispatchers.Main) {
                        setVerifyLoading(false)
                        showError(errorMsg)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setVerifyLoading(false)
                    showError("Ошибка сети. Проверьте подключение.")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session & subscription management
    // ─────────────────────────────────────────────────────────────────────────

    private fun saveSession(token: String, subUrl: String, email: String, expireDate: String = "") {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_TOKEN, token)
            putString(KEY_SUB_URL, subUrl)
            putString(KEY_EMAIL, email)
            if (expireDate.isNotBlank()) putString(KEY_EXPIRY, expireDate)
            apply()
        }
    }

    private suspend fun importSubscription(subUrl: String) = withContext(Dispatchers.IO) {
        try {
            // Check if LvovFlow group already exists
            val existingGroups = SagerDatabase.groupDao.allGroups()
            val existingGroup = existingGroups.find { it.name == "LvovFlow" }

            if (existingGroup != null) {
                // Update existing group's subscription URL
                existingGroup.subscription = SubscriptionBean().apply {
                    link = subUrl
                }
                withContext(Dispatchers.Main) {
                    GroupManager.updateGroup(existingGroup)
                }
                GroupUpdater.startUpdate(existingGroup, true)
            } else {
                // Create new group
                val group = ProxyGroup(type = GroupType.SUBSCRIPTION).apply {
                    name = "LvovFlow"
                    subscription = SubscriptionBean().apply {
                        link = subUrl
                    }
                }
                withContext(Dispatchers.Main) {
                    GroupManager.createGroup(group)
                }
                GroupUpdater.startUpdate(group, true)
            }
        } catch (e: Exception) {
            android.util.Log.e("LvovFlow", "Subscription import failed: ${e.message}", e)
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP helper
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun postJson(url: String, body: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-App-Client", "LvovFlow-Android")
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    doOutput = true
                }
                val json = JSONObject().apply {
                    body.forEach { (k, v) -> put(k, v) }
                }.toString()
                OutputStreamWriter(connection.outputStream).use { it.write(json) }
                // Read body from errorStream for 4xx/5xx, inputStream for 2xx
                val stream = if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: connection.inputStream
                }
                val response = stream.bufferedReader().readText()
                JSONObject(response)
            } finally {
                connection.disconnect()
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // UI Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun animateLogo() {
        logoIcon.alpha = 0f
        logoIcon.scaleX = 0.7f
        logoIcon.scaleY = 0.7f
        logoIcon.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(600)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun showKeyboard(view: View) {
        view.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    /**
     * Shows a subscription-expired blocking screen.
     * Called when Marzban returns status = expired/disabled.
     */
    private fun showExpiredScreen(expireDate: String) {
        // Hide both login steps completely
        stepEmail.visibility = View.GONE
        stepOtp.visibility = View.GONE
        hideError()

        // Build and show a non-dismissible dialog
        val dateText = if (expireDate.isNotBlank()) "Срок действия истёк $expireDate." else "Срок действия вашей подписки истёк."
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⏳ Подписка истекла")
            .setMessage("$dateText\n\nДля продолжения работы продлите подписку на сайте LvovFlow.")
            .setPositiveButton("Продлить подписку") { _, _ ->
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://lvovflow.com/#pricing"))
                )
            }
            .setNegativeButton("Выйти из аккаунта") { _, _ ->
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
                stepEmail.visibility = View.VISIBLE
                emailInput.text.clear()
                otpInput.text.clear()
            }
            .setCancelable(false)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
