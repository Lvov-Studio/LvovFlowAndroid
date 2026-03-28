package io.nekohasekai.sagernet.ui

import android.content.res.ColorStateList
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceDataStore
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
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
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ui.ServerBottomSheetAdapter
import io.nekohasekai.sagernet.database.proxy.ProxyEntity
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.utils.ProfileManager
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.isPlay
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import moe.matsuri.nb4a.utils.Util

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener,
    NavigationView.OnNavigationItemSelectedListener {

    lateinit var binding: LayoutMainBinding
    lateinit var navigation: NavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutMainBinding.inflate(layoutInflater)
        binding.fab.initProgress(binding.fabProgress)
        if (themeResId !in intArrayOf(
                R.style.Theme_SagerNet_Black
            )
        ) {
            navigation = binding.navView
            binding.drawerLayout.removeView(binding.navViewBlack)
        } else {
            navigation = binding.navViewBlack
            binding.drawerLayout.removeView(binding.navView)
        }
        navigation.setNavigationItemSelectedListener(this)

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        }
        onBackPressedDispatcher.addCallback {
            if (supportFragmentManager.findFragmentById(R.id.fragment_holder) is ConfigurationFragment) {
                moveTaskToBack(true)
            } else {
                displayFragmentWithId(R.id.nav_configuration)
            }
        }

        binding.fab.setOnClickListener {
            if (DataStore.serviceState.canStop) {
                SagerNet.stopService()
            } else {
                // LvovFlow: auto-select first profile if none chosen (list is hidden from user)
                if (DataStore.selectedProxy <= 0L) {
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
                                    onMainDispatcher {
                                        snackbar("Нет серверов. Нажмите «🔄 Обновить подключение» в меню.").show()
                                    }
                                }
                            } else {
                                onMainDispatcher {
                                    snackbar("Подписка не найдена. Войдите снова.").show()
                                }
                            }
                        } catch (e: Exception) {
                            onMainDispatcher { connect.launch(null) }
                        }
                    }
                } else {
                    connect.launch(null)
                }
            }
        }

        binding.stats.setOnClickListener { if (DataStore.serviceState.connected) binding.stats.testConnection() }

        setContentView(binding.root)

        // LvovFlow: If user is not authenticated, redirect to activation screen
        if (!ActivationActivity.isAuthenticated(this)) {
            startActivity(Intent(this, ActivationActivity::class.java))
            finish()
            return
        }
        changeState(BaseService.State.Idle)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        // LvovFlow: cache session data (UI no longer shows it here)
        val lvovPrefs = getSharedPreferences("lvovflow", android.content.Context.MODE_PRIVATE)
        val userEmail = lvovPrefs.getString("user_email", "") ?: ""
        val expireDate = lvovPrefs.getString("expire_date", "") ?: ""

        // LvovFlow: Open server selection bottom sheet
        binding.serverButtonContainer.setOnClickListener {
            showServerSelectionBottomSheet()
        }

        // LvovFlow: refresh subscription status from server in background
        val sessionToken = lvovPrefs.getString("session_token", "") ?: ""
        if (sessionToken.isNotBlank()) {
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
                        // Сохраняем свежие данные
                        lvovPrefs.edit().apply {
                            if (newExpire.isNotBlank()) putString("expire_date", newExpire)
                            if (newSubUrl.isNotBlank()) putString("subscription_url", newSubUrl)
                            apply()
                        }
                        // Обновляем UI на главном потоке (раньше тут обновлялась шапка меню)
                        withContext(Dispatchers.Main) {
                            // Header is now just a logo, nothing to update here
                        }
                    }
                } catch (_: Exception) {
                    // Без интернета — остаётся кэш
                }
            }
        }

        refreshNavMenu(DataStore.enableClashAPI)

        // LvovFlow: wire Профиль tab in bottom navigation
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.nav_bottom_profile) {
                startActivity(Intent(this, ProfileActivity::class.java))
                binding.bottomNav.post { binding.bottomNav.selectedItemId = R.id.nav_bottom_lk }
                false
            } else true
        }

        // sdk 33 notification
        if (Build.VERSION.SDK_INT >= 33) {
            val checkPermission =
                ContextCompat.checkSelfPermission(this@MainActivity, POST_NOTIFICATIONS)
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
                //动态申请
                ActivityCompat.requestPermissions(
                    this@MainActivity, arrayOf(POST_NOTIFICATIONS), 0
                )
            }
        }

        if (isPreview) {
            MaterialAlertDialogBuilder(this)
                .setTitle(BuildConfig.PRE_VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    fun refreshNavMenu(clashApi: Boolean) {
        // LvovFlow monolithic: nav_traffic and nav_tuiguang removed from menu
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            if (uri.scheme == "sn" && uri.host == "subscription" || uri.scheme == "clash") {
                importSubscription(uri)
            } else {
                importProfile(uri)
            }
        }
    }

    fun urlTest(): Int {
        if (!DataStore.serviceState.connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            // cleartext format
            subscription.link = url
            group.name = uri.getQueryParameter("name")
        } else {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group = KryoConverters.deserialize(
                    ProxyGroup().apply { export = true }, Util.zlibDecompress(Util.b64Decode(data))
                ).apply {
                    export = false
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    alert(e.readableMessage).show()
                }
                return
            }
        }

        val name = group.name.takeIf { !it.isNullOrBlank() } ?: group.subscription?.link
        ?: group.subscription?.token
        if (name.isNullOrBlank()) return

        group.name = group.name.takeIf { !it.isNullOrBlank() }
            ?: runCatching {
                // LvovFlow: use hostname as group name instead of "Subscription #timestamp"
                val subUrl = group.subscription?.link ?: group.subscription?.token
                if (!subUrl.isNullOrBlank()) {
                    android.net.Uri.parse(subUrl).host?.let { host ->
                        // Strip "www." prefix and use just the domain
                        host.removePrefix("www.")
                    }
                } else null
            }.getOrNull()
            ?: ("LvovFlow #" + (System.currentTimeMillis() % 10000))

        onMainDispatcher {

            displayFragmentWithId(R.id.nav_configuration)

            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
            displayFragmentWithId(R.id.nav_configuration)

            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        val pluginEntity = PluginEntry.find(pluginName)

        // unknown exe or neko plugin
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        // official exe

        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin, profileName, pluginEntity.displayName
                )
            )
            .setPositiveButton(R.string.action_download) { _, _ ->
                showDownloadDialog(pluginEntity)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_learn_more) { _, _ ->
                launchCustomTab("https://github.com/Lvov-Studio/LvovFlowAndroid")
            }
            .show()
    }

    private fun showDownloadDialog(pluginEntry: PluginEntry) {
        var index = 0
        var playIndex = -1
        var fdroidIndex = -1

        val items = mutableListOf<String>()
        if (pluginEntry.downloadSource.playStore) {
            items.add(getString(R.string.install_from_play_store))
            playIndex = index++
        }
        if (pluginEntry.downloadSource.fdroid) {
            items.add(getString(R.string.install_from_fdroid))
            fdroidIndex = index++
        }

        items.add(getString(R.string.download))
        val downloadIndex = index

        MaterialAlertDialogBuilder(this).setTitle(pluginEntry.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    playIndex -> launchCustomTab("https://play.google.com/store/apps/details?id=${pluginEntry.packageName}")
                    fdroidIndex -> launchCustomTab("https://f-droid.org/packages/${pluginEntry.packageName}/")
                    downloadIndex -> launchCustomTab(pluginEntry.downloadSource.downloadLink)
                }
            }
            .show()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.isChecked) binding.drawerLayout.closeDrawers() else {
            return displayFragmentWithId(item.itemId)
        }
        return true
    }


    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        if (fragment is ConfigurationFragment) {
            binding.stats.allowShow = true
            binding.fab.show()
            // LvovFlow: restore connection panel on main screen
            val isConnected = DataStore.serviceState == BaseService.State.Connected
            binding.connTimerLabel.visibility = if (isConnected) View.VISIBLE else View.GONE
            binding.connTimer.visibility = if (isConnected) View.VISIBLE else View.GONE
            binding.connStatusLabel.visibility = View.VISIBLE
        } else {
            if (!DataStore.showBottomBar) {
                binding.stats.allowShow = false
                binding.stats.performHide()
                binding.fab.hide()
            }
            // LvovFlow: ALWAYS hide connection panel on non-main screens
            binding.connTimerLabel.visibility = View.GONE
            binding.connTimer.visibility = View.GONE
            binding.connStatusLabel.visibility = View.GONE
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        binding.drawerLayout.closeDrawers()
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            R.id.nav_configuration -> {
                displayFragment(ConfigurationFragment())
            }
            R.id.nav_settings -> displayFragment(SettingsFragment())
            R.id.nav_faq -> {
                launchCustomTab("https://lvovflow.com")
                return false
            }
            R.id.nav_about -> displayFragment(AboutFragment())
            R.id.nav_refresh_subscription -> {
                // LvovFlow: check subscription status, then re-fetch if active
                binding.drawerLayout.closeDrawers()
                val prefs = getSharedPreferences("lvovflow", android.content.Context.MODE_PRIVATE)
                val token = prefs.getString("session_token", "") ?: ""
                if (token.isBlank()) {
                    snackbar("Сессия не найдена. Войдите снова.").show()
                    return false
                }
                snackbar("Проверяем подписку...").show()
                runOnDefaultDispatcher {
                    try {
                        // Call API to check current status
                        val url = java.net.URL("https://lvovflow.com/api/app/check_status.php")
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.setRequestProperty("X-App-Client", "LvovFlow-Android")
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 15_000
                        conn.doOutput = true
                        java.io.OutputStreamWriter(conn.outputStream).use {
                            it.write("{\"token\":\"$token\"}")
                        }
                        val body = conn.inputStream.bufferedReader().readText()
                        conn.disconnect()
                        val json = org.json.JSONObject(body)
                        val isExpired = json.optBoolean("is_expired", false)
                        val expireDate = json.optString("expire_date", "")
                        val newSubUrl = json.optString("subscription_url", "")

                        if (isExpired) {
                            onMainDispatcher {
                                val dateMsg = if (expireDate.isNotBlank()) "Срок действия истёк $expireDate." else "Срок действия вашей подписки истёк."
                                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                    .setTitle("⏳ Подписка истекла")
                                    .setMessage("$dateMsg\n\nДля продолжения работы продлите подписку на сайте LvovFlow.")
                                    .setPositiveButton("Продлить подписку") { _, _ ->
                                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://lvovflow.com/#pricing")))
                                    }
                                    .setNegativeButton("Выйти из аккаунта") { _, _ ->
                                        prefs.edit().clear().apply()
                                        startActivity(android.content.Intent(this@MainActivity, ActivationActivity::class.java).apply {
                                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        })
                                        finish()
                                    }
                                    .setCancelable(false)
                                    .show()
                            }
                        } else {
                            // Active — refresh subscription
                            val groups = SagerDatabase.groupDao.allGroups()
                            val sub = groups.firstOrNull { it.type == GroupType.SUBSCRIPTION }
                            if (sub != null) {
                                GroupUpdater.startUpdate(sub, true)
                                onMainDispatcher { snackbar("Подключение обновлено ✓").show() }
                            } else {
                                onMainDispatcher { snackbar("Подписка не найдена. Войдите снова.").show() }
                            }
                        }
                    } catch (e: Exception) {
                        onMainDispatcher { snackbar("Ошибка сети. Проверьте подключение.").show() }
                    }
                }
                return false
            }


            R.id.nav_logout -> {
                // LvovFlow: clear session and go to activation screen
                getSharedPreferences("lvovflow", android.content.Context.MODE_PRIVATE)
                    .edit().clear().apply()
                startActivity(
                    Intent(this, ActivationActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                )
                finish()
                return false
            }
            else -> return false
        }
        navigation.menu.findItem(id).isChecked = true
        return true
    }

    // LvovFlow: connection timer
    private var timerJob: Job? = null
    private var connectTime: Long = 0L
    private var breathAnimator: AnimatorSet? = null

    private fun startBreathAnimation() {
        breathAnimator?.cancel()
        val scaleX = ObjectAnimator.ofFloat(binding.fab, "scaleX", 1f, 1.08f, 1f).apply {
            duration = 2000L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
        }
        val scaleY = ObjectAnimator.ofFloat(binding.fab, "scaleY", 1f, 1.08f, 1f).apply {
            duration = 2000L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
        }
        breathAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            start()
        }
    }

    private fun stopBreathAnimation() {
        breathAnimator?.cancel()
        breathAnimator = null
        binding.fab.scaleX = 1f
        binding.fab.scaleY = 1f
    }

    // LvovFlow: pulsing ring animation (3 rings, staggered)
    private var pulseJob: Job? = null

    // LvovFlow: Server selection bottom sheet
    private fun showServerSelectionBottomSheet() {
        val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.Theme_LvovFlow_BottomSheetDialog)
        val view = layoutInflater.inflate(R.layout.layout_bottom_sheet_servers, null)
        bottomSheetDialog.setContentView(view)

        val recyclerView = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewServers)
        val profiles = ProfileManager.getProfiles()
        val adapter = ServerBottomSheetAdapter(profiles, DataStore.selectedProxy) { selectedProfile ->
            // Switch profile logic
            DataStore.selectedProxy = selectedProfile.id
            if (DataStore.serviceState == BaseService.State.Connected) {
                SagerNet.reloadService()
            }
            binding.connServerLabel.text = "Оптимальный сервер: " + selectedProfile.displayName()
            bottomSheetDialog.dismiss()
        }
        recyclerView.adapter = adapter
        bottomSheetDialog.show()
    }

    private fun animateRing(ring: View, delay: Long) {
        ring.scaleX = 1f
        ring.scaleY = 1f
        ring.alpha = 0.7f
        val scaleX = ObjectAnimator.ofFloat(ring, "scaleX", 1f, 1.8f).apply { duration = 2000L }
        val scaleY = ObjectAnimator.ofFloat(ring, "scaleY", 1f, 1.8f).apply { duration = 2000L }
        val alpha  = ObjectAnimator.ofFloat(ring, "alpha", 0.7f, 0f).apply { duration = 2000L }
        AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            startDelay = delay
            start()
        }
    }

    private fun startPulseAnimation() {
        stopPulseAnimation()
        pulseJob = lifecycleScope.launch {
            while (isActive) {
                animateRing(binding.pulseRing1, 0L)
                animateRing(binding.pulseRing2, 600L)
                animateRing(binding.pulseRing3, 1200L)
                delay(2800L)
            }
        }
    }

    private fun stopPulseAnimation() {
        pulseJob?.cancel()
        pulseJob = null
        if (::binding.isInitialized) {
            binding.pulseRing1.alpha = 0f
            binding.pulseRing2.alpha = 0f
            binding.pulseRing3.alpha = 0f
        }
    }

    private fun startConnectionTimer() {
        connectTime = System.currentTimeMillis()
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - connectTime
                val h = elapsed / 3_600_000L
                val m = (elapsed / 60_000L) % 60
                val s = (elapsed / 1_000L) % 60
                binding.connTimer.text = "%02d:%02d:%02d".format(h, m, s)
                delay(1_000L)
            }
        }
    }

    private fun stopConnectionTimer() {
        timerJob?.cancel()
        timerJob = null
        if (::binding.isInitialized) binding.connTimer.text = "00:00:00"
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
    ) {
        DataStore.serviceState = state

        binding.fab.changeState(state, DataStore.serviceState, animate)
        binding.stats.changeState(state)
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()

        // LvovFlow: FAB color — green when connected, indigo otherwise
        val fabColor = if (state == BaseService.State.Connected) {
            0xFF22C55E.toInt()  // bright green
        } else {
            com.google.android.material.color.MaterialColors.getColor(
                binding.fab, com.google.android.material.R.attr.colorPrimary
            )
        }
        binding.fab.backgroundTintList = ColorStateList.valueOf(fabColor)

        // LvovFlow: timer + status + server card + speed row + pulse rings
        if (state == BaseService.State.Connected) {
            binding.connTimerLabel.visibility = View.VISIBLE
            binding.connTimer.visibility = View.VISIBLE
            binding.connStatusLabel.text = "Соединение активно"
            // Server label small (now a button container)
            binding.serverButtonContainer.visibility = View.VISIBLE
            val profileId = DataStore.selectedProxy
            val serverName = if (profileId > 0L) {
                runCatching { ProfileManager.getProfile(profileId)?.displayName() }.getOrNull()
                    ?: "LvovFlow"
            } else "LvovFlow"
            binding.connServerLabel.text = "Оптимальный сервер: $serverName"
            
            startConnectionTimer()
            startBreathAnimation()
            startPulseAnimation()
        } else {
            binding.connTimerLabel.visibility = View.GONE
            binding.connTimer.visibility = View.GONE
            binding.serverButtonContainer.visibility = View.GONE
            binding.connStatusLabel.text = when (state) {
                BaseService.State.Connecting -> "Подключение..."
                BaseService.State.Stopping -> "Отключение..."
                else -> "Активировать ускорение"
            }
            stopConnectionTimer()
            stopBreathAnimation()
            stopPulseAnimation()
        }
    }


    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            if (binding.fab.isShown) {
                anchorView = binding.fab
            }
            // TODO
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
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

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    // may NOT called when app is in background
    // ONLY do UI update here, write DB in bg process
    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        binding.stats.updateSpeed(stats.txRateProxy, stats.rxRateProxy)
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

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SERVICE_MODE -> onBinderDied()
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (DataStore.serviceState.canStop) {
                    snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                        SagerNet.reloadService()
                    }.show()
                }
            }
        }
    }

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
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (super.onKeyDown(keyCode, event)) return true
                binding.drawerLayout.open()
                navigation.requestFocus()
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.drawerLayout.isOpen) {
                    binding.drawerLayout.close()
                    return true
                }
            }
        }

        if (super.onKeyDown(keyCode, event)) return true
        if (binding.drawerLayout.isOpen) return false

        val fragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

}
