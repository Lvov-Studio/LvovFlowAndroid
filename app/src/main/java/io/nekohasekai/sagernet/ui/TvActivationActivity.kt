package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
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
 * LvovFlow — TV Activation Screen
 * Same OTP flow as ActivationActivity but with TV-optimized layout.
 * All auth logic is identical to the mobile version.
 */
class TvActivationActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "lvovflow"
        private const val KEY_TOKEN = "session_token"
        private const val KEY_SUB_URL = "subscription_url"
        private const val KEY_EMAIL = "user_email"
        private const val KEY_EXPIRY = "expire_date"
        private const val API_BASE = "https://lvovflow.com/api/app"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentEmail: String = ""

    private lateinit var stepEmail: LinearLayout
    private lateinit var stepOtp: LinearLayout
    private lateinit var emailInput: EditText
    private lateinit var otpInput: EditText
    private lateinit var btnSendCode: Button
    private lateinit var btnVerify: Button
    private lateinit var btnBack: Button
    private lateinit var btnResend: Button
    private lateinit var otpHint: TextView
    private lateinit var errorLabel: TextView
    private lateinit var emailProgress: LinearLayout
    private lateinit var verifyProgress: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ActivationActivity.isAuthenticated(this)) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_tv_activation)

        stepEmail = findViewById(R.id.tv_step_email)
        stepOtp = findViewById(R.id.tv_step_otp)
        emailInput = findViewById(R.id.tv_email_input)
        otpInput = findViewById(R.id.tv_otp_input)
        btnSendCode = findViewById(R.id.tv_btn_send_code)
        btnVerify = findViewById(R.id.tv_btn_verify)
        btnBack = findViewById(R.id.tv_btn_back)
        btnResend = findViewById(R.id.tv_btn_resend)
        otpHint = findViewById(R.id.tv_otp_hint)
        errorLabel = findViewById(R.id.tv_error_label)
        emailProgress = findViewById(R.id.tv_email_progress)
        verifyProgress = findViewById(R.id.tv_verify_progress)

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
        btnBack.setOnClickListener { showEmailStep() }

        // Resend
        btnResend.setOnClickListener {
            if (currentEmail.isNotBlank()) sendCode(resend = true)
        }

        // Auto-focus email field for immediate TV keyboard input
        emailInput.requestFocus()
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
        otpHint.text = "Код отправлен на $email"
        stepEmail.visibility = View.GONE
        stepOtp.visibility = View.VISIBLE
        hideError()
        otpInput.text?.clear()
        otpInput.requestFocus()
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
        emailProgress.visibility = if (loading) View.VISIBLE else View.GONE
        emailInput.isEnabled = !loading
    }

    private fun setVerifyLoading(loading: Boolean) {
        btnVerify.isEnabled = !loading
        verifyProgress.visibility = if (loading) View.VISIBLE else View.GONE
        otpInput.isEnabled = !loading
        btnResend.isEnabled = !loading
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Network calls (identical to ActivationActivity)
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendCode(resend: Boolean = false) {
        val email = emailInput.text.toString().trim().lowercase()
        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError("Введите корректный email адрес")
            return
        }
        hideError()
        setEmailLoading(true)

        scope.launch {
            try {
                val result = postJson("$API_BASE/send_otp.php", mapOf("email" to email))
                val ok = result.optBoolean("ok", false)
                if (ok) {
                    withContext(Dispatchers.Main) {
                        setEmailLoading(false)
                        showOtpStep(email)
                        if (resend) Toast.makeText(this@TvActivationActivity, "Код отправлен повторно", Toast.LENGTH_SHORT).show()
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
        setVerifyLoading(true)

        scope.launch {
            try {
                val result = postJson(
                    "$API_BASE/verify_otp.php",
                    mapOf("email" to currentEmail, "code" to code)
                )
                val ok = result.optBoolean("ok", false)
                if (ok) {
                    val token = result.optString("token", "")
                    val subUrl = result.optString("subscription_url", "")
                    val isExpired = result.optBoolean("is_expired", false)
                    val expireDate = result.optString("expire_date", "")

                    if (token.isBlank() || subUrl.isBlank()) {
                        withContext(Dispatchers.Main) {
                            setVerifyLoading(false)
                            showError("Ошибка сервера. Попробуйте снова.")
                        }
                        return@launch
                    }

                    saveSession(token, subUrl, currentEmail, expireDate)

                    if (isExpired) {
                        withContext(Dispatchers.Main) {
                            setVerifyLoading(false)
                            showExpiredScreen(expireDate)
                        }
                        return@launch
                    }

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
    // Session & subscription
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
        } catch (e: Exception) {
            // Silently fail — subscription will be imported on next sync
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, TvMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun showExpiredScreen(expireDate: String) {
        stepEmail.visibility = View.GONE
        stepOtp.visibility = View.GONE
        hideError()

        val dateText = if (expireDate.isNotBlank()) "Срок действия истёк $expireDate."
                       else "Срок действия вашей подписки истёк."
        AlertDialog.Builder(this)
            .setTitle("⏳ Подписка истекла")
            .setMessage("$dateText\n\nДля продолжения работы продлите подписку на сайте LvovFlow.")
            .setPositiveButton("Продлить подписку") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://lvovflow.com/#pricing")))
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

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP helper (identical to ActivationActivity)
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

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
