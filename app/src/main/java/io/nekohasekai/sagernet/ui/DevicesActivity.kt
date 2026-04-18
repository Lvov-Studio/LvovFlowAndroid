package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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

/**
 * LvovFlow — My Devices Screen
 * Three features:
 * 1. QR Login — scan QR code on TV to authorize
 * 2. Subscription Key — show/copy subscription URL + QR
 * 3. Active Sessions — list and manage logged-in devices
 */
class DevicesActivity : AppCompatActivity() {

    companion object {
        private const val API_BASE = "https://lvovflow.com/api/app"
    }

    private lateinit var sessionToken: String
    private lateinit var subscriptionUrl: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_devices)

        window.statusBarColor = 0xFF0B0E14.toInt()
        window.navigationBarColor = 0xFF0B0E14.toInt()
        supportActionBar?.hide()

        val prefs = getSharedPreferences("lvovflow", MODE_PRIVATE)
        sessionToken = prefs.getString("session_token", "") ?: ""
        subscriptionUrl = prefs.getString("subscription_url", "") ?: ""

        // Back
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        // ── QR Login card ──
        setupQrLogin()

        // ── Subscription Key card ──
        setupSubKey()

        // ── Active Sessions ── 
        loadSessions()

        // Terminate all
        findViewById<TextView>(R.id.btn_terminate_all).setOnClickListener {
            confirmTerminateAll()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 1. QR LOGIN — scan QR on TV
    // ═══════════════════════════════════════════════════════════════════

    private fun setupQrLogin() {
        findViewById<LinearLayout>(R.id.card_qr_login).setOnClickListener {
            val intent = Intent(this, TvQrScannerActivity::class.java)
            startActivityForResult(intent, 1001)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            val scannedUrl = data.getStringExtra(TvQrScannerActivity.RESULT_QR_CONTENT) ?: ""

            if (scannedUrl.contains("tv-pair") || scannedUrl.contains("code=")) {
                val code = extractPairCode(scannedUrl)
                if (code.isNotBlank()) {
                    confirmTvPairing(code)
                } else {
                    Toast.makeText(this, "Неверный QR-код", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Это не QR-код LvovFlow TV", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun extractPairCode(url: String): String {
        // Extract 'code' parameter from URL like https://lvovflow.com/tv-pair.php?code=AB12CD
        val regex = Regex("[?&]code=([A-Za-z0-9]+)")
        return regex.find(url)?.groupValues?.get(1) ?: ""
    }

    private fun confirmTvPairing(pairCode: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("📺 Авторизовать ТВ?")
            .setMessage("Код: $pairCode\n\nТелевизор получит доступ к вашему аккаунту LvovFlow.")
            .setPositiveButton("Авторизовать") { _, _ ->
                executeTvPairing(pairCode)
            }
            .setNegativeButton("Отмена", null)
            .create()
        dialog.show()
        styleDialogButtons(dialog)
    }

    private fun executeTvPairing(pairCode: String) {
        lifecycleScope.launch {
            try {
                val result = postJson("$API_BASE/tv_pair.php", JSONObject().apply {
                    put("action", "confirm")
                    put("pair_code", pairCode)
                    put("session_token", sessionToken)
                })

                if (result.optBoolean("ok")) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@DevicesActivity, "✅ Телевизор авторизован!", Toast.LENGTH_LONG).show()
                        // Reload sessions to show new TV session
                        loadSessions()
                    }
                } else {
                    val error = result.optString("error", "Ошибка")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@DevicesActivity, error, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DevicesActivity, "Ошибка сети", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 2. SUBSCRIPTION KEY — show URL + QR
    // ═══════════════════════════════════════════════════════════════════

    private var keyExpanded = false

    private fun setupSubKey() {
        val keyDetails = findViewById<LinearLayout>(R.id.key_details)
        val tvSubUrl = findViewById<TextView>(R.id.tv_sub_url)
        val ivSubQr = findViewById<ImageView>(R.id.iv_sub_qr)
        val btnCopy = findViewById<ImageButton>(R.id.btn_copy_url)

        tvSubUrl.text = subscriptionUrl

        // Toggle expand
        findViewById<LinearLayout>(R.id.card_sub_key).setOnClickListener {
            keyExpanded = !keyExpanded
            keyDetails.visibility = if (keyExpanded) View.VISIBLE else View.GONE

            if (keyExpanded && ivSubQr.drawable == null && subscriptionUrl.isNotBlank()) {
                // Generate QR on first expand
                lifecycleScope.launch(Dispatchers.IO) {
                    val qr = generateQrBitmap(subscriptionUrl, 512)
                    withContext(Dispatchers.Main) {
                        ivSubQr.setImageBitmap(qr)
                    }
                }
            }
        }

        // Copy URL
        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("sub_url", subscriptionUrl))
            Toast.makeText(this, "✅ Ключ скопирован", Toast.LENGTH_SHORT).show()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 3. ACTIVE SESSIONS — list and manage
    // ═══════════════════════════════════════════════════════════════════

    private fun loadSessions() {
        val loading = findViewById<LinearLayout>(R.id.sessions_loading)
        val list = findViewById<LinearLayout>(R.id.sessions_list)
        val error = findViewById<TextView>(R.id.sessions_error)
        val btnTermAll = findViewById<TextView>(R.id.btn_terminate_all)

        loading.visibility = View.VISIBLE
        list.visibility = View.GONE
        error.visibility = View.GONE
        btnTermAll.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val result = postJson("$API_BASE/sessions.php", JSONObject().apply {
                    put("action", "list")
                    put("session_token", sessionToken)
                })

                if (result.optBoolean("ok")) {
                    val sessions = result.getJSONArray("sessions")
                    withContext(Dispatchers.Main) {
                        loading.visibility = View.GONE
                        list.visibility = View.VISIBLE
                        list.removeAllViews()

                        for (i in 0 until sessions.length()) {
                            val s = sessions.getJSONObject(i)
                            if (i > 0) {
                                // Divider
                                val divider = View(this@DevicesActivity).apply {
                                    layoutParams = LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                                    ).apply {
                                        marginStart = 16.dp()
                                        marginEnd = 16.dp()
                                    }
                                    setBackgroundColor(0x0DFFFFFF.toInt())
                                }
                                list.addView(divider)
                            }
                            list.addView(createSessionRow(s))
                        }

                        if (sessions.length() > 1) {
                            btnTermAll.visibility = View.VISIBLE
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        loading.visibility = View.GONE
                        error.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loading.visibility = View.GONE
                    error.text = "Ошибка сети"
                    error.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun createSessionRow(session: JSONObject): LinearLayout {
        val id = session.optInt("id")
        val device = session.optString("device", "phone")
        val createdAt = session.optString("created_at", "")
        val isCurrent = session.optBoolean("is_current", false)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(20.dp(), 14.dp(), 16.dp(), 14.dp())
        }

        // Device icon (Material, no emoji)
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(22.dp(), 22.dp())
            setImageResource(if (device == "tv") R.drawable.ic_devices else R.drawable.ic_navigation_apps)
            setColorFilter(if (isCurrent) 0xFF00F0FF.toInt() else 0xFF64748B.toInt())
        }
        row.addView(icon)

        // Info column
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 14.dp()
            }
        }

        val deviceName = TextView(this).apply {
            text = when (device) {
                "tv" -> "Android TV"
                else -> "Телефон"
            } + if (isCurrent) " · это устройство" else ""
            setTextColor(if (isCurrent) 0xFF00F0FF.toInt() else 0xFFE2E8F0.toInt())
            textSize = 14f
        }
        infoCol.addView(deviceName)

        val details = TextView(this).apply {
            text = createdAt   // IP убран — всегда 127.0.0.1, не информативен
            setTextColor(0xFF64748B.toInt())
            textSize = 12f
        }
        infoCol.addView(details)

        row.addView(infoCol)

        // Revoke button — soft icon, no aggressive red X
        if (!isCurrent) {
            val revokeBtn = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(36.dp(), 36.dp()).apply {
                    marginStart = 8.dp()
                }
                setImageResource(R.drawable.ic_navigation_close)
                setColorFilter(0xFF64748B.toInt())   // subtle grey, not red
                // subtle dark circle background
                setBackgroundResource(android.R.drawable.btn_default)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0x14FFFFFF.toInt())
                }
                setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
                isClickable = true
                isFocusable = true
                setOnClickListener { revokeSession(id) }
            }
            row.addView(revokeBtn)
        }

        return row
    }


    private fun revokeSession(sessionId: Int) {
        AlertDialog.Builder(this)
            .setTitle("Завершить сессию?")
            .setMessage("Устройство будет отключено от вашего аккаунта.")
            .setPositiveButton("Завершить") { _, _ ->
                lifecycleScope.launch {
                    try {
                        postJson("$API_BASE/sessions.php", JSONObject().apply {
                            put("action", "revoke")
                            put("session_id", sessionId)
                            put("session_token", sessionToken)
                        })
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@DevicesActivity, "Сессия завершена", Toast.LENGTH_SHORT).show()
                            loadSessions()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@DevicesActivity, "Ошибка", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .create().also { it.show(); styleDialogButtons(it) }
    }

    private fun confirmTerminateAll() {
        AlertDialog.Builder(this)
            .setTitle("Завершить все сессии?")
            .setMessage("Все устройства кроме текущего будут отключены.")
            .setPositiveButton("Завершить все") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val result = postJson("$API_BASE/sessions.php", JSONObject().apply {
                            put("action", "revoke_all")
                            put("session_token", sessionToken)
                        })
                        val count = result.optInt("revoked", 0)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@DevicesActivity, "Завершено сессий: $count", Toast.LENGTH_SHORT).show()
                            loadSessions()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@DevicesActivity, "Ошибка", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .create().also { it.show(); styleDialogButtons(it) }
    }

    private fun styleDialogButtons(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFF00F0FF.toInt())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFF8B949E.toInt())
    }

    // ═══════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════

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
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    private suspend fun postJson(url: String, body: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-App-Client", "LvovFlow-Android")
                    setRequestProperty("X-Session-Token", sessionToken)
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    doOutput = true
                }
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                val stream = if (conn.responseCode in 200..299) conn.inputStream
                             else conn.errorStream ?: conn.inputStream
                JSONObject(stream.bufferedReader().readText())
            } finally {
                conn.disconnect()
            }
        }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
