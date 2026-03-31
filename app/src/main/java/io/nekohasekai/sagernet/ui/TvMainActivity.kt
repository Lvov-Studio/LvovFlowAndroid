package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * LvovFlow — TV Main Activity
 * Landscape VPN control screen optimized for D-Pad navigation.
 * Reuses the same VPN service layer as mobile MainActivity.
 */
class TvMainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener {

    // VPN connection
    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) {
            Toast("VPN permission denied")
        }
    }

    // UI views
    private lateinit var btnConnect: Button
    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var speedRow: LinearLayout
    private lateinit var tvSpeedDown: TextView
    private lateinit var tvSpeedUp: TextView
    private lateinit var tvIpInfo: TextView
    private lateinit var tvGlow: View
    private lateinit var subBadge: TextView
    private lateinit var versionText: TextView

    // Timer
    private var timerJob: Job? = null
    private var connectTime: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Auth check
        if (!ActivationActivity.isAuthenticated(this)) {
            startActivity(Intent(this, TvActivationActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_tv_main)

        // Bind views
        btnConnect = findViewById(R.id.tv_btn_connect)
        statusText = findViewById(R.id.tv_status_text)
        timerText = findViewById(R.id.tv_timer)
        speedRow = findViewById(R.id.tv_speed_row)
        tvSpeedDown = findViewById(R.id.tv_speed_down)
        tvSpeedUp = findViewById(R.id.tv_speed_up)
        tvIpInfo = findViewById(R.id.tv_ip_info)
        tvGlow = findViewById(R.id.tv_glow)
        subBadge = findViewById(R.id.tv_sub_badge)
        versionText = findViewById(R.id.tv_version)

        versionText.text = "v${BuildConfig.VERSION_NAME}"

        // Connect button
        btnConnect.setOnClickListener {
            if (DataStore.serviceState.canStop) {
                SagerNet.stopService()
            } else {
                autoSelectAndConnect()
            }
        }

        // Request initial focus on the connect button
        btnConnect.requestFocus()

        // Subscription button
        findViewById<Button>(R.id.tv_btn_subscription).setOnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java))
        }

        // About button
        findViewById<Button>(R.id.tv_btn_about).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("LvovFlow v${BuildConfig.VERSION_NAME}")
                .setMessage("Акселератор интернета\n\n© 2026 Lvov Studio\nhttps://lvovflow.com")
                .setPositiveButton("OK", null)
                .show()
        }

        // Logout button
        findViewById<Button>(R.id.tv_btn_logout).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Выйти?")
                .setMessage("Вы уверены, что хотите выйти из аккаунта?")
                .setPositiveButton("Выйти") { _, _ ->
                    getSharedPreferences("lvovflow", MODE_PRIVATE).edit().clear().apply()
                    startActivity(Intent(this, TvActivationActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        // Force-stop stale VPN after update
        val prefs = getSharedPreferences("lvovflow", MODE_PRIVATE)
        val lastVersion = prefs.getString("last_app_version", "")
        if (lastVersion != BuildConfig.VERSION_NAME) {
            SagerNet.stopService()
            prefs.edit().putString("last_app_version", BuildConfig.VERSION_NAME).apply()
        }

        // Initialize VPN state
        changeState(BaseService.State.Idle)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)

        // Background checks
        checkAppUpdate()
        refreshSubscriptionStatus()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VPN connection management
    // ─────────────────────────────────────────────────────────────────────────

    private fun autoSelectAndConnect() {
        val needAutoSelect = DataStore.selectedProxy <= 0L ||
            SagerDatabase.proxyDao.getById(DataStore.selectedProxy) == null
        if (needAutoSelect) {
            runOnDefaultDispatcher {
                try {
                    val groups = SagerDatabase.groupDao.allGroups()
                    val sub = groups.firstOrNull { it.type == GroupType.SUBSCRIPTION }
                    if (sub != null) {
                        val profiles = SagerDatabase.proxyDao.getByGroup(sub.id)
                        val first = profiles.firstOrNull()
                        if (first != null) {
                            DataStore.selectedProxy = first.id
                            onMainDispatcher { connect.launch(null) }
                        } else {
                            onMainDispatcher { Toast("Обновляем серверы...") }
                            refreshSubscriptionAndConnect()
                        }
                    } else {
                        onMainDispatcher { Toast("Подписка не найдена. Войдите снова.") }
                    }
                } catch (e: Exception) {
                    onMainDispatcher { connect.launch(null) }
                }
            }
        } else {
            connect.launch(null)
        }
    }

    private suspend fun refreshSubscriptionAndConnect() {
        try {
            val groups = SagerDatabase.groupDao.allGroups()
            val sub = groups.firstOrNull { it.type == GroupType.SUBSCRIPTION }
            if (sub != null) {
                GroupUpdater.startUpdate(sub, true)
                delay(3000)
                val profiles = SagerDatabase.proxyDao.getByGroup(sub.id)
                val first = profiles.firstOrNull()
                if (first != null) {
                    DataStore.selectedProxy = first.id
                    onMainDispatcher { connect.launch(null) }
                } else {
                    onMainDispatcher { Toast("Не удалось загрузить серверы.") }
                }
            }
        } catch (e: Exception) {
            onMainDispatcher { Toast("Ошибка обновления: ${e.message}") }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VPN state handling
    // ─────────────────────────────────────────────────────────────────────────

    private fun changeState(state: BaseService.State, msg: String? = null) {
        DataStore.serviceState = state

        // Button appearance
        if (state == BaseService.State.Connected) {
            btnConnect.text = "⏸"
            btnConnect.backgroundTintList = ColorStateList.valueOf(0xFF22C55E.toInt())
            tvGlow.backgroundTintList = ColorStateList.valueOf(0xFF22C55E.toInt())
            tvGlow.alpha = 0.5f
            statusText.text = "Ускорение активно"
            statusText.setTextColor(0xFF22C55E.toInt())

            timerText.visibility = View.VISIBLE
            speedRow.visibility = View.VISIBLE

            startConnectionTimer()
            fetchExternalIp()
        } else {
            btnConnect.text = "⚡"
            btnConnect.backgroundTintList = ColorStateList.valueOf(0xFF25C9EF.toInt())
            tvGlow.backgroundTintList = null
            tvGlow.alpha = 0.4f
            statusText.setTextColor(0xFFE2E8F0.toInt())
            statusText.text = when (state) {
                BaseService.State.Connecting -> "Подключение..."
                BaseService.State.Stopping -> "Отключение..."
                else -> "Активировать ускорение"
            }

            timerText.visibility = View.GONE
            speedRow.visibility = View.GONE
            tvIpInfo.visibility = View.GONE

            stopConnectionTimer()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SagerConnection.Callback implementation
    // ─────────────────────────────────────────────────────────────────────────

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg)
    }

    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)

    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        runOnUiThread {
            tvSpeedDown.text = "↓ ${formatSpeed(stats.rxRateProxy)}"
            tvSpeedUp.text = "↑ ${formatSpeed(stats.txRateProxy)}"
        }
    }

    override fun cbTrafficUpdate(data: TrafficData) {
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(data)
        }
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Timer & Speed
    // ─────────────────────────────────────────────────────────────────────────

    private fun startConnectionTimer() {
        connectTime = System.currentTimeMillis()
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - connectTime
                val h = elapsed / 3_600_000L
                val m = (elapsed / 60_000L) % 60
                val s = (elapsed / 1_000L) % 60
                timerText.text = "%02d:%02d:%02d".format(h, m, s)
                delay(1_000L)
            }
        }
    }

    private fun stopConnectionTimer() {
        timerJob?.cancel()
        timerJob = null
        timerText.text = "00:00:00"
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1_000_000L -> String.format("%.1f Мб/с", bytesPerSec / 1_000_000.0)
            bytesPerSec >= 1_000L -> String.format("%.0f Кб/с", bytesPerSec / 1_000.0)
            else -> "$bytesPerSec Б/с"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IP detection
    // ─────────────────────────────────────────────────────────────────────────

    private fun fetchExternalIp() {
        lifecycleScope.launch(Dispatchers.IO) {
            delay(2000) // Wait for VPN tunnel to stabilize
            for (attempt in 1..2) {
                try {
                    val url = URL("http://ip-api.com/json?fields=query,countryCode")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.setRequestProperty("User-Agent", "LvovFlow-Android")
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val json = JSONObject(body)
                    val ip = json.optString("query", "")
                    val cc = json.optString("countryCode", "")
                    val flag = countryCodeToFlag(cc)
                    if (ip.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            tvIpInfo.text = "IP: $ip  •  $flag $cc"
                            tvIpInfo.visibility = View.VISIBLE
                        }
                        break
                    }
                } catch (_: Exception) { }
                if (attempt < 2) delay(3000)
            }
        }
    }

    private fun countryCodeToFlag(cc: String): String {
        if (cc.length != 2) return ""
        val first = Character.toChars(0x1F1E6 - 'A'.code + cc[0].uppercaseChar().code)
        val second = Character.toChars(0x1F1E6 - 'A'.code + cc[1].uppercaseChar().code)
        return String(first) + String(second)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // App update & sync (same as MainActivity)
    // ─────────────────────────────────────────────────────────────────────────

    private fun checkAppUpdate() {
        runOnDefaultDispatcher {
            try {
                val prefs = getSharedPreferences("lvovflow", MODE_PRIVATE)
                val email = prefs.getString("user_email", "") ?: ""
                val deviceId = android.provider.Settings.Secure.getString(
                    contentResolver, android.provider.Settings.Secure.ANDROID_ID
                ) ?: "unknown_device"
                val model = Build.MANUFACTURER + " " + Build.MODEL

                val url = URL("https://lvovflow.com/api/app/app_sync.php")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true

                val payload = JSONObject().apply {
                    put("email", email)
                    put("device_id", deviceId)
                    put("device_model", model)
                    put("app_version", BuildConfig.VERSION_NAME)
                }.toString()
                OutputStreamWriter(conn.outputStream).use { it.write(payload) }

                if (conn.responseCode != 200) return@runOnDefaultDispatcher
                val content = conn.inputStream.bufferedReader().readText()
                if (content.isBlank()) return@runOnDefaultDispatcher
                val response = JSONObject(content)

                // Remote logout check
                if (!response.optBoolean("ok", true) && response.optBoolean("logged_out", false)) {
                    onMainDispatcher {
                        if (DataStore.serviceState.started) SagerNet.stopService()
                        prefs.edit().clear().apply()
                        MaterialAlertDialogBuilder(this@TvMainActivity)
                            .setTitle("Выход с устройства")
                            .setMessage("Это устройство было удалено из вашего аккаунта.")
                            .setPositiveButton("Войти") { _, _ ->
                                startActivity(Intent(this@TvMainActivity, TvActivationActivity::class.java))
                                finishAffinity()
                            }
                            .setCancelable(false)
                            .show()
                    }
                    return@runOnDefaultDispatcher
                }

                // Device limit
                if (!response.optBoolean("ok", true) && response.optString("error") == "device_limit") {
                    onMainDispatcher {
                        MaterialAlertDialogBuilder(this@TvMainActivity)
                            .setTitle("⚠️ Лимит устройств")
                            .setMessage("Достигнут лимит устройств (2). Удалите устройство в личном кабинете.")
                            .setCancelable(false)
                            .setPositiveButton("OK") { _, _ -> finishAffinity() }
                            .show()
                    }
                    return@runOnDefaultDispatcher
                }

                // Update check
                if (response.has("update") && !response.isNull("update")) {
                    val updateObj = response.getJSONObject("update")
                    val serverVersion = updateObj.optString("versionName", "")
                    val releaseUrl = updateObj.optString("url", "https://lvovflow.com/app/LvovFlow-latest.apk")
                    val changelog = updateObj.optString("changelog", "Оптимизация и стабильность.")

                    if (serverVersion.isNotBlank() && serverVersion != BuildConfig.VERSION_NAME) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(this@TvMainActivity)
                                .setTitle("Обновление: $serverVersion")
                                .setMessage(changelog)
                                .setPositiveButton("Обновить") { _, _ ->
                                    if (DataStore.serviceState.started) SagerNet.stopService()
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)))
                                }
                                .setNegativeButton("Позже", null)
                                .show()
                        }
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun refreshSubscriptionStatus() {
        val prefs = getSharedPreferences("lvovflow", MODE_PRIVATE)
        val sessionToken = prefs.getString("session_token", "") ?: ""
        if (sessionToken.isBlank()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("https://lvovflow.com/api/app/status.php")
                    .openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-App-Client", "LvovFlow-Android")
                    setRequestProperty("X-Session-Token", sessionToken)
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    doOutput = true
                }
                OutputStreamWriter(conn.outputStream).use { it.write("{}") }
                val stream = if (conn.responseCode in 200..299) conn.inputStream
                             else conn.errorStream ?: conn.inputStream
                val json = JSONObject(stream.bufferedReader().readText())
                conn.disconnect()
                if (json.optBoolean("ok")) {
                    val newExpire = json.optString("expire_date", "")
                    val newSubUrl = json.optString("subscription_url", "")
                    prefs.edit().apply {
                        if (newExpire.isNotBlank()) putString("expire_date", newExpire)
                        if (newSubUrl.isNotBlank()) putString("subscription_url", newSubUrl)
                        apply()
                    }
                }
            } catch (_: Exception) { }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // D-Pad: Enter/OK on connect button = same as click
    // ─────────────────────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val focused = currentFocus
            if (focused == btnConnect) {
                btnConnect.performClick()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SERVICE_MODE -> onBinderDied()
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (DataStore.serviceState.canStop) {
                    SagerNet.reloadService()
                }
            }
        }
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(findViewById(android.R.id.content), text, Snackbar.LENGTH_LONG)
    }

    private fun Toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }
}
