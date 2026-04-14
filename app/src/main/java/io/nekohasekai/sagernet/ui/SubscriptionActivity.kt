package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * LvovFlow — Subscription Detail Screen
 * Shows current plan status, features included, and renew/upgrade options.
 * On TV: landscape layout with QR-code payment (no browser needed).
 * On Phone: portrait layout with browser redirect to YuKassa.
 */
class SubscriptionActivity : AppCompatActivity() {

    private val isTv by lazy { TvUtils.isTv(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Transparent nav/status bars
        window.statusBarColor = 0xFF0B0E14.toInt()
        window.navigationBarColor = 0xFF0B0E14.toInt()

        if (isTv) {
            setupTvLayout()
        } else {
            setupPhoneLayout()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // PHONE layout (original)
    // ═══════════════════════════════════════════════════════════════════

    private fun setupPhoneLayout() {
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

        // Plan name
        findViewById<TextView>(R.id.tv_plan_name).text = "Flow"

        // Expire date
        val tvExpire = findViewById<TextView>(R.id.tv_expire_date)
        val tvDaysLeft = findViewById<TextView>(R.id.tv_days_left)
        val tvStatusBadge = findViewById<TextView>(R.id.tv_status_badge)
        val dotStatus = findViewById<View>(R.id.dot_status)
        val badgeStatus = findViewById<LinearLayout>(R.id.badge_status)

        setupExpireDisplay(expireDate, tvExpire, tvDaysLeft, tvStatusBadge, dotStatus, badgeStatus)

        // Renew button → payment (browser)
        findViewById<Button>(R.id.btn_renew).setOnClickListener {
            val url = "https://lvovflow.com/api/payment/create.php?token=$sessionToken"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        // Upgrade button → tariffs tab
        findViewById<LinearLayout>(R.id.btn_upgrade).setOnClickListener {
            finish()
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("navigate_to_tab", "tariffs")
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // TV layout (landscape + QR payment)
    // ═══════════════════════════════════════════════════════════════════

    private fun setupTvLayout() {
        setContentView(R.layout.activity_tv_subscription)
        supportActionBar?.hide()

        val prefs = getSharedPreferences("lvovflow", Context.MODE_PRIVATE)
        val expireDate = prefs.getString("expire_date", "") ?: ""
        val sessionToken = prefs.getString("session_token", "") ?: ""

        // Back button
        findViewById<android.widget.ImageButton>(R.id.tv_sub_btn_back).setOnClickListener {
            finish()
        }

        // Plan name
        findViewById<TextView>(R.id.tv_plan_name).text = "Flow"

        // Expire date
        val tvExpire = findViewById<TextView>(R.id.tv_expire_date)
        val tvDaysLeft = findViewById<TextView>(R.id.tv_days_left)
        val tvStatusBadge = findViewById<TextView>(R.id.tv_status_badge)
        val dotStatus = findViewById<View>(R.id.dot_status)
        val badgeStatus = findViewById<LinearLayout>(R.id.badge_status)

        setupExpireDisplay(expireDate, tvExpire, tvDaysLeft, tvStatusBadge, dotStatus, badgeStatus)

        // QR views
        val qrImage = findViewById<ImageView>(R.id.tv_qr_image)
        val qrLoading = findViewById<ProgressBar>(R.id.tv_qr_loading)
        val qrError = findViewById<TextView>(R.id.tv_qr_error)
        val btnRenew = findViewById<Button>(R.id.btn_renew)

        // Initially hide QR image, show loading
        qrImage.visibility = View.GONE
        qrLoading.visibility = View.VISIBLE
        qrError.visibility = View.GONE

        // Generate QR on load
        generatePaymentQr(sessionToken, qrImage, qrLoading, qrError)

        // Renew button = regenerate QR
        btnRenew.setOnClickListener {
            qrImage.visibility = View.GONE
            qrLoading.visibility = View.VISIBLE
            qrError.visibility = View.GONE
            generatePaymentQr(sessionToken, qrImage, qrLoading, qrError)
        }

        // Focus management for D-Pad
        btnRenew.requestFocus()

        setupTvFocusAnimations(btnRenew,
            findViewById(R.id.tv_sub_btn_back))
    }

    // ═══════════════════════════════════════════════════════════════════
    // QR code generation (request payment URL from server, encode as QR)
    // ═══════════════════════════════════════════════════════════════════

    private fun generatePaymentQr(
        sessionToken: String,
        qrImage: ImageView,
        qrLoading: ProgressBar,
        qrError: TextView
    ) {
        if (sessionToken.isBlank()) {
            qrLoading.visibility = View.GONE
            qrError.text = "Войдите в аккаунт для оплаты"
            qrError.visibility = View.VISIBLE
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Request payment URL from server
                val conn = URL("https://lvovflow.com/api/payment/create.php")
                    .openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-App-Client", "LvovFlow-Android-TV")
                    setRequestProperty("X-Session-Token", sessionToken)
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    doOutput = true
                }
                OutputStreamWriter(conn.outputStream).use {
                    it.write(JSONObject().apply {
                        put("session_token", sessionToken)
                    }.toString())
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream
                             else conn.errorStream ?: conn.inputStream
                val body = stream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(body)
                if (json.optBoolean("ok") && json.has("payment_url")) {
                    val paymentUrl = json.getString("payment_url")
                    // Generate QR bitmap
                    val qrBitmap = generateQrBitmap(paymentUrl, 512)

                    withContext(Dispatchers.Main) {
                        qrLoading.visibility = View.GONE
                        qrImage.setImageBitmap(qrBitmap)
                        qrImage.visibility = View.VISIBLE
                    }
                } else {
                    val errorMsg = json.optString("error", "Ошибка создания платежа")
                    withContext(Dispatchers.Main) {
                        qrLoading.visibility = View.GONE
                        qrError.text = errorMsg
                        qrError.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    qrLoading.visibility = View.GONE
                    qrError.text = "Ошибка сети: ${e.message}"
                    qrError.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * Generate QR code bitmap without any external library.
     * Uses a simple bit-matrix encoding (no Reed-Solomon, basic QR).
     * For production, we generate a URL-based QR via Canvas drawing.
     */
    private fun generateQrBitmap(data: String, size: Int): Bitmap {
        // Use Android's built-in QR encoding if available via ZXing (bundled with many ROMs)
        // Fallback: manual pixel drawing
        try {
            // Try ZXing (many Android TV devices have Google Play Services with this)
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
            // ZXing not available — create a placeholder with instructions
            return createFallbackBitmap(size, data)
        }
    }

    /**
     * Fallback if ZXing is not available — creates an image with payment URL text
     */
    private fun createFallbackBitmap(size: Int, url: String): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = android.graphics.Paint().apply {
            color = Color.BLACK
            textSize = 24f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }

        canvas.drawText("Откройте на телефоне:", size / 2f, size / 2f - 40f, paint)

        paint.textSize = 16f
        paint.color = 0xFF0EA5E9.toInt()

        // Word-wrap the URL
        val maxWidth = size - 40f
        val lines = mutableListOf<String>()
        var remaining = url
        while (remaining.isNotEmpty()) {
            val count = paint.breakText(remaining, true, maxWidth, null)
            lines.add(remaining.substring(0, count))
            remaining = remaining.substring(count)
        }

        var y = size / 2f
        for (line in lines) {
            canvas.drawText(line, size / 2f, y, paint)
            y += 22f
        }

        return bitmap
    }

    // ═══════════════════════════════════════════════════════════════════
    // Shared helpers
    // ═══════════════════════════════════════════════════════════════════

    private fun setupExpireDisplay(
        expireDate: String,
        tvExpire: TextView,
        tvDaysLeft: TextView,
        tvStatusBadge: TextView,
        dotStatus: View,
        badgeStatus: LinearLayout
    ) {
        if (expireDate.isNotBlank()) {
            tvExpire.text = "Активна до $expireDate"

            try {
                val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                val expDate = sdf.parse(expireDate)
                if (expDate != null) {
                    val diff = expDate.time - System.currentTimeMillis()
                    val daysLeft = TimeUnit.MILLISECONDS.toDays(diff).toInt()

                    if (daysLeft > 0) {
                        tvDaysLeft.text = "Осталось $daysLeft ${pluralDays(daysLeft)}"
                        tvStatusBadge.text = "Активна"
                        tvStatusBadge.setTextColor(0xFF00E676.toInt())
                        dotStatus.backgroundTintList = ColorStateList.valueOf(0xFF00E676.toInt())
                        badgeStatus.backgroundTintList = ColorStateList.valueOf(0x1A00E676)
                    } else {
                        tvDaysLeft.text = "Подписка истекла"
                        tvStatusBadge.text = "Истекла"
                        tvStatusBadge.setTextColor(0xFFEF4444.toInt())
                        dotStatus.backgroundTintList = ColorStateList.valueOf(0xFFEF4444.toInt())
                        badgeStatus.backgroundTintList = ColorStateList.valueOf(0x1AEF4444)
                        tvExpire.setTextColor(0xFFEF4444.toInt())
                    }
                }
            } catch (_: Exception) {
                tvDaysLeft.text = "—"
            }
        } else {
            tvExpire.text = "Подписка активна"
            tvDaysLeft.text = ""
        }
    }

    private fun pluralDays(n: Int): String = when {
        n % 10 == 1 && n % 100 != 11 -> "день"
        n % 10 in 2..4 && (n % 100 < 10 || n % 100 >= 20) -> "дня"
        else -> "дней"
    }

    private fun setupTvFocusAnimations(vararg views: View) {
        for (view in views) {
            view.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.05f else 1.0f
                val elevation = if (hasFocus) 8f else 0f
                v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .translationZ(elevation)
                    .setDuration(150)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
    }
}
