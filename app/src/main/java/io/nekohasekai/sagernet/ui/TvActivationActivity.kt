package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * LvovFlow — TV Activation Screen
 * Two login methods:
 *   1. Email + OTP (original, via TV keyboard)
 *   2. QR Code (scan with phone, confirm in browser)
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
    private var pollJob: Job? = null
    private var currentPairCode: String = ""

    // Email/OTP views
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

    // Tab buttons
    private lateinit var tabEmail: Button
    private lateinit var tabQr: Button

    // QR views
    private lateinit var stepQr: LinearLayout
    private lateinit var qrLoginImage: ImageView
    private lateinit var qrLoginLoading: ProgressBar
    private lateinit var pairCodeLabel: TextView
    private lateinit var pairCodeText: TextView
    private lateinit var qrStatus: TextView
    private lateinit var btnRefreshQr: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ActivationActivity.isAuthenticated(this)) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_tv_activation)

        // Bind Email/OTP views
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

        // Bind tab buttons
        tabEmail = findViewById(R.id.tv_tab_email)
        tabQr = findViewById(R.id.tv_tab_qr)

        // Bind QR views
        stepQr = findViewById(R.id.tv_step_qr)
        qrLoginImage = findViewById(R.id.tv_qr_login_image)
        qrLoginLoading = findViewById(R.id.tv_qr_login_loading)
        pairCodeLabel = findViewById(R.id.tv_pair_code_label)
        pairCodeText = findViewById(R.id.tv_pair_code)
        qrStatus = findViewById(R.id.tv_qr_status)
        btnRefreshQr = findViewById(R.id.tv_btn_refresh_qr)

        // ── Tab switching ──
        tabEmail.setOnClickListener { switchToEmailTab() }
        tabQr.setOnClickListener { switchToQrTab() }

        // ── Email/OTP handlers ──
        btnSendCode.setOnClickListener { sendCode() }
        emailInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { sendCode(); true } else false
        }
        btnVerify.setOnClickListener { verifyCode() }
        otpInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { verifyCode(); true } else false
        }
        btnBack.setOnClickListener { showEmailStep() }
        btnResend.setOnClickListener {
            if (currentEmail.isNotBlank()) sendCode(resend = true)
        }

        // ── QR handlers ──
        btnRefreshQr.setOnClickListener { requestPairingCode() }

        // Start with email tab focused
        emailInput.requestFocus()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tab switching
    // ─────────────────────────────────────────────────────────────────────────

    private fun switchToEmailTab() {
        // Visual
        tabEmail.backgroundTintList = ColorStateList.valueOf(0xFF25C9EF.toInt())
        tabEmail.setTextColor(0xFFFFFFFF.toInt())
        tabQr.backgroundTintList = ColorStateList.valueOf(0x00000000)
        tabQr.setTextColor(0xFF7099C0.toInt())

        // Show email, hide QR
        stepQr.visibility = View.GONE
        stepOtp.visibility = View.GONE
        stepEmail.visibility = View.VISIBLE
        hideError()
        emailInput.requestFocus()
        stopPolling()
    }

    private fun switchToQrTab() {
        // Visual
        tabQr.backgroundTintList = ColorStateList.valueOf(0xFF25C9EF.toInt())
        tabQr.setTextColor(0xFFFFFFFF.toInt())
        tabEmail.backgroundTintList = ColorStateList.valueOf(0x00000000)
        tabEmail.setTextColor(0xFF7099C0.toInt())

        // Show QR, hide email/otp
        stepEmail.visibility = View.GONE
        stepOtp.visibility = View.GONE
        stepQr.visibility = View.VISIBLE
        hideError()

        // Request new pairing code
        requestPairingCode()
        btnRefreshQr.requestFocus()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // QR Pairing
    // ─────────────────────────────────────────────────────────────────────────

    private fun requestPairingCode() {
        stopPolling()
        qrLoginImage.visibility = View.GONE
        qrLoginLoading.visibility = View.VISIBLE
        pairCodeLabel.visibility = View.GONE
        pairCodeText.visibility = View.GONE
        qrStatus.visibility = View.GONE
        btnRefreshQr.visibility = View.GONE

        scope.launch {
            try {
                val result = postJson("$API_BASE/tv_pair.php", mapOf("action" to "create"))
                if (result.optBoolean("ok")) {
                    val pairCode = result.optString("pair_code", "")
                    val pairUrl = result.optString("pair_url", "")
                    currentPairCode = pairCode

                    if (pairUrl.isNotBlank()) {
                        val qrBitmap = withContext(Dispatchers.IO) {
                            generateQrBitmap(pairUrl, 512)
                        }

                        withContext(Dispatchers.Main) {
                            qrLoginLoading.visibility = View.GONE
                            qrLoginImage.setImageBitmap(qrBitmap)
                            qrLoginImage.visibility = View.VISIBLE
                            pairCodeLabel.visibility = View.VISIBLE
                            pairCodeText.text = pairCode
                            pairCodeText.visibility = View.VISIBLE
                            qrStatus.text = "Ожидание подтверждения..."
                            qrStatus.visibility = View.VISIBLE
                            btnRefreshQr.visibility = View.VISIBLE
                        }

                        // Start polling
                        startPolling(pairCode)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        qrLoginLoading.visibility = View.GONE
                        showError("Не удалось создать QR-код")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    qrLoginLoading.visibility = View.GONE
                    showError("Ошибка сети: ${e.message}")
                }
            }
        }
    }

    private fun startPolling(pairCode: String) {
        stopPolling()
        pollJob = scope.launch {
            // Poll every 3 seconds for up to 10 minutes
            val maxAttempts = 200  // 200 * 3s = 600s = 10 min
            for (i in 0 until maxAttempts) {
                if (!isActive) break
                delay(3000)

                try {
                    val result = withContext(Dispatchers.IO) {
                        val conn = URL("$API_BASE/tv_pair.php")
                            .openConnection() as HttpURLConnection
                        conn.apply {
                            requestMethod = "POST"
                            setRequestProperty("Content-Type", "application/json")
                            setRequestProperty("X-App-Client", "LvovFlow-Android")
                            connectTimeout = 5000
                            readTimeout = 5000
                            doOutput = true
                        }
                        val body = JSONObject().apply {
                            put("action", "poll")
                            put("pair_code", pairCode)
                        }.toString()
                        OutputStreamWriter(conn.outputStream).use { it.write(body) }
                        val stream = if (conn.responseCode in 200..299) conn.inputStream
                                     else conn.errorStream ?: conn.inputStream
                        val response = stream.bufferedReader().readText()
                        conn.disconnect()
                        JSONObject(response)
                    }

                    val status = result.optString("status", "")
                    when (status) {
                        "confirmed" -> {
                            // Success! Extract session data
                            val token = result.optString("token", "")
                            val subUrl = result.optString("subscription_url", "")
                            val email = result.optString("email", "")
                            val expireDate = result.optString("expire_date", "")

                            if (token.isNotBlank() && subUrl.isNotBlank()) {
                                saveSession(token, subUrl, email, expireDate)
                                importSubscription(subUrl)

                                withContext(Dispatchers.Main) {
                                    qrStatus.text = "✅ Авторизовано!"
                                    qrStatus.setTextColor(0xFF22C55E.toInt())
                                    delay(1000)
                                    goToMain()
                                }
                                return@launch
                            }
                        }
                        "expired" -> {
                            withContext(Dispatchers.Main) {
                                qrStatus.text = "QR-код истёк. Обновите."
                                qrStatus.setTextColor(0xFFEF4444.toInt())
                            }
                            return@launch
                        }
                        // "pending" — continue polling
                    }
                } catch (_: Exception) {
                    // Network error — continue polling silently
                }
            }

            // Timeout
            withContext(Dispatchers.Main) {
                qrStatus.text = "Время ожидания истекло. Обновите QR."
                qrStatus.setTextColor(0xFFEF4444.toInt())
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // QR Bitmap generation (ZXing)
    // ─────────────────────────────────────────────────────────────────────────

    private fun generateQrBitmap(data: String, size: Int): Bitmap {
        try {
            val hints = java.util.EnumMap<com.google.zxing.EncodeHintType, Any>(
                com.google.zxing.EncodeHintType::class.java
            )
            hints[com.google.zxing.EncodeHintType.MARGIN] = 1
            hints[com.google.zxing.EncodeHintType.ERROR_CORRECTION] =
                com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M

            val matrix = com.google.zxing.qrcode.QRCodeWriter()
                .encode(data, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)

            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            return bitmap
        } catch (e: Exception) {
            // Fallback
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = android.graphics.Paint().apply {
                color = Color.BLACK
                textSize = 28f
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
            }
            canvas.drawText("QR Error", size / 2f, size / 2f, paint)
            return bitmap
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI transitions (Email/OTP — unchanged)
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
    // Network calls (Email/OTP — unchanged)
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
        stepQr.visibility = View.GONE
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
        stopPolling()
        scope.cancel()
    }
}
