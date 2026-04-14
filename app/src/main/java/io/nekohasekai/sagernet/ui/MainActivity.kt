package io.nekohasekai.sagernet.ui

import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.view.animation.LinearInterpolator
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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import libcore.Libcore
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
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
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

        // LvovFlow: show onboarding on first launch
        val prefs = getSharedPreferences("lvovflow", MODE_PRIVATE)
        if (!prefs.getBoolean("onboarding_shown", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        binding = LayoutMainBinding.inflate(layoutInflater)
        binding.fab.initProgress(binding.fabProgress)

        // LvovFlow Concept: Wire custom VPN button to hidden FAB
        binding.vpnButtonCore.setOnClickListener {
            binding.fab.performClick()
        }
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

        // LvovFlow: completely lock the navigation drawer — all nav is via bottom bar
        binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

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
            // LvovFlow: premium haptic feedback on tap (light pulse)
            performLionHaptic(light = true)
            if (DataStore.serviceState.canStop) {
                SagerNet.stopService()
            } else {
                // LvovFlow: auto-select first profile if none chosen or profile was deleted
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
                                    // No profiles in subscription — try auto-refresh
                                    onMainDispatcher {
                                        snackbar("Обновляем серверы...").show()
                                    }
                                    refreshSubscriptionAndConnect()
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

        // removed binding.stats.setOnClickListener

        setContentView(binding.root)

        // LvovFlow: If user is not authenticated, redirect to activation screen
        if (!ActivationActivity.isAuthenticated(this)) {
            startActivity(Intent(this, ActivationActivity::class.java))
            finish()
            return
        }

        // Check for updates silently
        checkAppUpdate()

        // LvovFlow: Force-stop stale VPN service after app update
        // If the app was updated while VPN was running, the service is dead but
        // state may still be "Connected". Detect version change and force cleanup.
        val lastVersion = prefs.getString("last_app_version", "")
        val currentVersion = BuildConfig.VERSION_NAME
        if (lastVersion != currentVersion) {
            // Version changed — force stop any orphaned VPN tunnel
            SagerNet.stopService()
            prefs.edit().putString("last_app_version", currentVersion).apply()
        }

        changeState(BaseService.State.Idle)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        // LvovFlow: cache session data (UI no longer shows it here)
        val lvovPrefs = getSharedPreferences("lvovflow", android.content.Context.MODE_PRIVATE)
        val userEmail = lvovPrefs.getString("user_email", "") ?: ""
        val expireDate = lvovPrefs.getString("expire_date", "") ?: ""

        // LvovFlow: server selection removed — using connection map instead

        // LvovFlow: refresh subscription status + home banner from server
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
                    val responseCode = conn.responseCode

                    // Session expired/invalid → redirect to login
                    if (responseCode == 401) {
                        conn.disconnect()
                        withContext(Dispatchers.Main) {
                            lvovPrefs.edit().remove("session_token").apply()
                            startActivity(Intent(this@MainActivity, ActivationActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            })
                            finish()
                        }
                        return@launch
                    }

                    val stream = if (responseCode in 200..299) conn.inputStream
                                 else conn.errorStream ?: conn.inputStream
                    val json = JSONObject(stream.bufferedReader().readText())
                    conn.disconnect()
                    if (json.optBoolean("ok")) {
                        val newExpire = json.optString("expire_date", "")
                        val newSubUrl = json.optString("subscription_url", "")
                        lvovPrefs.edit().apply {
                            if (newExpire.isNotBlank()) putString("expire_date", newExpire)
                            if (newSubUrl.isNotBlank()) putString("subscription_url", newSubUrl)
                            apply()
                        }
                    }
                } catch (_: Exception) { }
            }
            // Load home banner in parallel
            loadHomeBanner(sessionToken)
        }

        refreshNavMenu(DataStore.enableClashAPI)

        // LvovFlow: wire tabs in bottom navigation
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_bottom_lk -> {
                    displayFragmentWithId(R.id.nav_configuration)
                    true
                }
                R.id.nav_bottom_profile -> {
                    displayFragmentWithId(R.id.nav_bottom_profile)
                    true
                }
                R.id.nav_bottom_tariffs -> {
                    displayFragmentWithId(R.id.nav_bottom_tariffs)
                    true
                }
                R.id.nav_bottom_ai -> {
                    displayFragmentWithId(R.id.nav_bottom_ai)
                    true
                }
                else -> true
            }
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
        if (!item.isChecked) {
            return displayFragmentWithId(item.itemId)
        }
        return true
    }


    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: androidx.fragment.app.Fragment) {
        val isMain = fragment is ConfigurationFragment
        val isConnected = DataStore.serviceState == BaseService.State.Connected

        if (isMain) {
            // Only show home UI elements if the container is actually visible
            val containerVisible = binding.mainHomeContainer.visibility == View.VISIBLE
            if (containerVisible) {
                binding.speedRow.visibility = if (isConnected) View.VISIBLE else View.GONE
                binding.ipPill.visibility = if (isConnected) View.VISIBLE else View.GONE

                if (isConnected) {
                    binding.glowBg.visibility = View.VISIBLE
                    startOrbitAnimation()
                    startBreathAnimation()
                }
            }
        } else {
            binding.speedRow.visibility = View.GONE
            binding.ipPill.visibility = View.GONE

            binding.glowBg.visibility = View.GONE
            stopOrbitAnimation()
            stopBreathAnimation()
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        // LvovFlow: drawer disabled, no need to close
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        // LvovFlow: header (status pill + chat) stays visible on ALL tabs
        val isHome = id == R.id.nav_configuration
        binding.mainHomeContainer.visibility = View.VISIBLE
        // Hide home-specific content on non-home tabs, but keep header
        binding.headerRow.visibility = View.VISIBLE
        binding.orbitContainer.visibility = if (isHome) View.VISIBLE else View.GONE
        binding.statusText.visibility = if (isHome) View.VISIBLE else View.GONE
        binding.lowerContent.visibility = if (isHome) View.VISIBLE else View.GONE
        binding.glowBg.visibility = if (isHome && binding.glowBg.alpha > 0f) View.VISIBLE else View.GONE
        binding.shockwave1.visibility = if (isHome) View.VISIBLE else View.GONE
        binding.shockwave2.visibility = if (isHome) View.VISIBLE else View.GONE
        // Connection map is outside mainHomeContainer, hide separately
        if (!isHome) binding.connectionMap.visibility = View.GONE
        // Fragment holder needs top margin on non-home tabs to not overlap header
        val fragmentParams = binding.fragmentHolder.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
        if (fragmentParams != null) {
            fragmentParams.topMargin = if (isHome) 0 else (56 * resources.displayMetrics.density).toInt()
            binding.fragmentHolder.layoutParams = fragmentParams
        }

        when (id) {
            R.id.nav_configuration -> {
                displayFragment(ConfigurationFragment())
            }
            R.id.nav_bottom_profile -> {
                displayFragment(ProfileFragment())
            }
            R.id.nav_bottom_tariffs -> {
                displayFragment(TariffsFragment())
            }
            R.id.nav_bottom_ai -> {
                displayFragment(AiFragment())
            }
            R.id.nav_settings -> displayFragment(SettingsFragment())
            R.id.nav_faq -> {
                launchCustomTab("https://lvovflow.com")
                return false
            }
            R.id.nav_about -> displayFragment(AboutFragment())
            R.id.nav_refresh_subscription -> {
                // LvovFlow: check subscription status, then re-fetch if active
                // LvovFlow: drawer disabled
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
        navigation.menu.findItem(id)?.isChecked = true
        binding.bottomNav.menu.findItem(id)?.isChecked = true
        return true
    }

    // LvovFlow: connection state tracking
    private var wasConnected: Boolean = false
    private var connectTime: Long = 0L
    private var breathAnimator: AnimatorSet? = null
    private var orbitInnerAnimator: ObjectAnimator? = null
    private var orbitOuterAnimator: ObjectAnimator? = null

    // LvovFlow Concept: Session traffic totals
    private var totalSessionRx: Long = 0L
    private var totalSessionTx: Long = 0L
    private var lastSpeedTimestamp: Long = 0L

    private fun startBreathAnimation() {
        breathAnimator?.cancel()
        val target = binding.vpnButtonCore
        val scaleX = ObjectAnimator.ofFloat(target, "scaleX", 1f, 1.06f, 1f).apply {
            duration = 2500L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
        }
        val scaleY = ObjectAnimator.ofFloat(target, "scaleY", 1f, 1.06f, 1f).apply {
            duration = 2500L
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
        if (::binding.isInitialized) {
            binding.vpnButtonCore.scaleX = 1f
            binding.vpnButtonCore.scaleY = 1f
        }
    }

    // LvovFlow Concept: Orbit ring rotation animations
    private fun startOrbitAnimation() {
        stopOrbitAnimation()
        orbitInnerAnimator = ObjectAnimator.ofFloat(binding.orbitInner, "rotation", 0f, 360f).apply {
            duration = 20000L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        orbitOuterAnimator = ObjectAnimator.ofFloat(binding.orbitOuter, "rotation", 360f, 0f).apply {
            duration = 30000L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        binding.orbitInner.alpha = 1f
        binding.orbitOuter.alpha = 1f
    }

    private fun stopOrbitAnimation() {
        orbitInnerAnimator?.cancel()
        orbitOuterAnimator?.cancel()
        orbitInnerAnimator = null
        orbitOuterAnimator = null
        if (::binding.isInitialized) {
            binding.orbitInner.alpha = 0.2f
            binding.orbitOuter.alpha = 0.2f
        }
    }

    // LvovFlow: Server selection bottom sheet
    private fun showServerSelectionBottomSheet(profiles: List<ProxyEntity>) {
        val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_bottom_sheet_servers, null)
        bottomSheetDialog.setContentView(view)

        val recyclerView = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewServers)
        val adapter = ServerBottomSheetAdapter(profiles, DataStore.selectedProxy) { selectedProfile ->
            // Switch profile logic
            DataStore.selectedProxy = selectedProfile.id
            if (DataStore.serviceState == BaseService.State.Connected) {
                SagerNet.reloadService()
            }
            bottomSheetDialog.dismiss()
        }
        recyclerView.adapter = adapter
        bottomSheetDialog.show()
    }

    /**
     * LvovFlow: Shockwave animation — two rings expand outward from the FAB
     * when VPN connects. Creates a smooth "sonar pulse" effect.
     */
    private fun playShockwaveAnimation() {
        if (!::binding.isInitialized) return
        val ring1 = binding.shockwave1
        val ring2 = binding.shockwave2

        fun animateShockwaveRing(ring: View, delayMs: Long) {
            ring.scaleX = 1f
            ring.scaleY = 1f
            ring.alpha = 0.5f
            ring.animate()
                .setStartDelay(delayMs)
                .scaleX(4.5f)
                .scaleY(4.5f)
                .alpha(0f)
                .setDuration(1200)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
                .start()
        }

        animateShockwaveRing(ring1, 0L)
        animateShockwaveRing(ring2, 400L)
    }

    // LvovFlow: Load home screen banner from server (subscription status + admin messages)
    private fun loadHomeBanner(token: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("https://lvovflow.com/api/app/home_config.php")
                    .openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-App-Client", "LvovFlow-Android")
                    setRequestProperty("X-Session-Token", token)
                    connectTimeout = 6_000
                    readTimeout = 6_000
                    doOutput = true
                }
                OutputStreamWriter(conn.outputStream).use { it.write("{}") }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json = JSONObject(body)
                if (!json.optBoolean("ok")) return@launch

                val sub = json.optJSONObject("subscription")
                val banner = json.optJSONObject("banner")

                withContext(Dispatchers.Main) {
                    if (sub != null) {
                        val expireDate = sub.optString("expire_date", "")
                        val isExpired = sub.optBoolean("is_expired", false)
                        val daysLeft = if (sub.isNull("days_left")) null else sub.optInt("days_left")

                        val statusText = when {
                            isExpired -> "❌ Подписка истекла"
                            expireDate.isNotBlank() && daysLeft != null && daysLeft <= 7 ->
                                "⚠️ Истекает через $daysLeft ${pluralDays(daysLeft)}"
                            expireDate.isNotBlank() -> "✓ Активна до $expireDate"
                            else -> "✓ Подписка активна"
                        }

                        val statusColor = when {
                            isExpired -> 0xFFEF4444.toInt()
                            daysLeft != null && daysLeft <= 7 -> 0xFFF59E0B.toInt()
                            else -> 0xFF22C55E.toInt()
                        }

                        binding.tvSubscriptionStatus.text = statusText
                        binding.tvSubscriptionStatus.setTextColor(statusColor)
                        binding.homeBanner.visibility = View.VISIBLE

                        // LvovFlow Concept: Update status pill badge with expire date
                        if (!isExpired && expireDate.isNotBlank()) {
                            binding.tvStatusBadge.text = "До $expireDate"
                        }
                    }

                    if (banner != null && banner.optBoolean("visible")) {
                        val msgText = banner.optString("text", "")
                        val action = banner.optString("action", "")
                        if (msgText.isNotBlank()) {
                            binding.tvAdminMessage.text = msgText
                            binding.tvAdminMessage.visibility = View.VISIBLE
                            if (action.isNotBlank()) {
                                binding.homeBanner.setOnClickListener {
                                    when (action) {
                                        "tariffs" -> displayFragmentWithId(R.id.nav_bottom_tariffs)
                                        "profile" -> displayFragmentWithId(R.id.nav_bottom_profile)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Без интернета — используем кэш из SharedPreferences
                val prefs = getSharedPreferences("lvovflow", android.content.Context.MODE_PRIVATE)
                val expireDate = prefs.getString("expire_date", "") ?: ""
                if (expireDate.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        binding.tvSubscriptionStatus.text = "✓ Активна до $expireDate"
                        binding.tvSubscriptionStatus.setTextColor(0xFF22C55E.toInt())
                        binding.homeBanner.visibility = View.VISIBLE
                        // Update status pill badge from cache
                        binding.tvStatusBadge.text = "До $expireDate"
                    }
                }
            }
        }
    }

    private fun pluralDays(n: Int): String = when {
        n % 10 == 1 && n % 100 != 11 -> "день"
        n % 10 in 2..4 && (n % 100 < 10 || n % 100 >= 20) -> "дня"
        else -> "дней"
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
    ) {
        DataStore.serviceState = state

        binding.fab.changeState(state, DataStore.serviceState, animate)
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()

        // LvovFlow: haptic feedback on successful connection (strong pulse)
        if (state == BaseService.State.Connected && animate) {
            performLionHaptic(light = false)
        }

        // LvovFlow Concept: Update UI based on connection state
        val isOnHomeTab = binding.orbitContainer.visibility == View.VISIBLE

        if (state == BaseService.State.Connected) {
            // ── ON state: concept aesthetic ──
            if (isOnHomeTab) {
                // Status text
                binding.statusText.text = "LvovFlow Активен"
                binding.statusText.setTextColor(0xFFFFFFFF.toInt())

                // VPN button → active gradient
                binding.vpnButtonCore.setBackgroundResource(R.drawable.bg_vpn_button_active)
                binding.shieldIcon.setImageResource(R.drawable.ic_service_idle)
                binding.shieldIcon.setColorFilter(0xFFFFFFFF.toInt())

                // Speed & stats visible
                binding.speedRow.visibility = View.VISIBLE
                binding.speedSparkline.visibility = View.VISIBLE
                binding.ipPill.visibility = View.VISIBLE

                // Routing path visible
                binding.routingPath.visibility = View.VISIBLE
                binding.routeLineFill.visibility = View.VISIBLE

                // Glow behind button
                binding.glowBg.visibility = View.VISIBLE
                binding.glowBg.alpha = 0.30f

                // Lower content fully visible
                binding.lowerContent.alpha = 1f

                // Background gradient (green radial)
                binding.coordinator.setBackgroundResource(R.drawable.bg_app_gradient_on)

                // Status pill → active (green)
                binding.statusPillInner.setBackgroundResource(R.drawable.bg_status_pill_active)
                binding.statusDot.backgroundTintList = ColorStateList.valueOf(0xFF00E676.toInt())
                binding.tvStatusBadge.setTextColor(0xFF00E676.toInt())

                // Orbit + breath animations
                startOrbitAnimation()
                startBreathAnimation()
                playShockwaveAnimation()
            }

            animateNavBarColor(0xFF0B0E14.toInt())
            fetchExternalIp()

            if (!wasConnected) {
                connectTime = System.currentTimeMillis()
            }
            wasConnected = true
        } else {
            // ── OFF / Connecting / Stopping state ──
            if (isOnHomeTab) {
                binding.statusText.text = when (state) {
                    BaseService.State.Connecting -> "Подключение..."
                    BaseService.State.Stopping -> "Отключение..."
                    else -> "Активировать ускорение"
                }
                binding.statusText.setTextColor(0xFF8B949E.toInt())

                // VPN button → idle (dim)
                binding.vpnButtonCore.setBackgroundResource(R.drawable.bg_vpn_button_idle)
                binding.shieldIcon.setColorFilter(0xFF8B949E.toInt())

                // Speed & stats: keep visible but show idle data
                binding.speedRow.visibility = View.VISIBLE
                binding.speedSparkline.visibility = View.VISIBLE
                binding.speedSparkline.clear()
                binding.ipPill.visibility = View.VISIBLE
                binding.tvSpeedDown.text = "↓ 0 Б/с"
                binding.tvSpeedUp.text = "↑ 0 Б/с"
                binding.tvSessionDown.text = "Загрузка · 0 Б"
                binding.tvSessionUp.text = "Отдача · 0 Б"
                binding.tvIpInfo.text = "IP: —"
                binding.tvFlag.text = ""
                binding.tvCountryCode.text = "—"

                // Routing path stays visible, line fill hidden
                binding.routingPath.visibility = View.VISIBLE
                binding.routeLineFill.visibility = View.GONE

                // Glow off
                binding.glowBg.visibility = View.GONE

                // Lower content dimmed (like concept — visible but faded)
                binding.lowerContent.animate()
                    .alpha(0.3f)
                    .setDuration(400)
                    .start()

                // Background solid dark
                binding.coordinator.setBackgroundColor(0xFF0B0E14.toInt())

                // Status pill → inactive (grey)
                binding.statusPillInner.setBackgroundResource(R.drawable.bg_status_pill_inactive)
                binding.statusDot.backgroundTintList = ColorStateList.valueOf(0xFF8B949E.toInt())
                binding.tvStatusBadge.setTextColor(0xFF8B949E.toInt())
                if (state == BaseService.State.Idle) {
                    binding.tvStatusBadge.text = "Нет подключения"
                }
            }

            animateNavBarColor(0xFF0B0E14.toInt())

            stopBreathAnimation()
            stopOrbitAnimation()

            if (state == BaseService.State.Idle && wasConnected) {
                clearMobileSessionStats()
                // Reset session traffic counters
                totalSessionRx = 0L
                totalSessionTx = 0L
                lastSpeedTimestamp = 0L
            }
            wasConnected = false
        }
    }


    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            // Show snackbar above the bottom navigation bar
            anchorView = binding.bottomNav
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
    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        runOnUiThread {
            binding.tvSpeedDown.text = "↓ ${formatSpeed(stats.rxRateProxy)}"
            binding.tvSpeedUp.text = "↑ ${formatSpeed(stats.txRateProxy)}"

            // Accumulate session totals (callback fires ~every 1 second)
            val now = System.currentTimeMillis()
            val elapsed = if (lastSpeedTimestamp > 0) (now - lastSpeedTimestamp) / 1000.0 else 1.0
            lastSpeedTimestamp = now
            totalSessionRx += (stats.rxRateProxy * elapsed).toLong()
            totalSessionTx += (stats.txRateProxy * elapsed).toLong()

            binding.tvSessionDown.text = "Загрузка · ${formatBytes(totalSessionRx)}"
            binding.tvSessionUp.text = "Отдача · ${formatBytes(totalSessionTx)}"

            // Feed sparkline with download speed
            binding.speedSparkline.addSpeed(stats.rxRateProxy)
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1_000_000L -> String.format("%.1f Мб/с", bytesPerSec / 1_000_000.0)
            bytesPerSec >= 1_000L -> String.format("%.0f Кб/с", bytesPerSec / 1_000.0)
            else -> "$bytesPerSec Б/с"
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000L -> String.format("%.1f ГБ", bytes / 1_000_000_000.0)
            bytes >= 1_000_000L -> String.format("%.1f МБ", bytes / 1_000_000.0)
            bytes >= 1_000L -> String.format("%.0f КБ", bytes / 1_000.0)
            else -> "$bytes Б"
        }
    }

    private fun animateNavBarColor(targetColor: Int) {
        val from = window.navigationBarColor
        if (from == targetColor) return
        android.animation.ValueAnimator.ofArgb(from, targetColor).apply {
            duration = 400
            addUpdateListener { window.navigationBarColor = it.animatedValue as Int }
            start()
        }
    }

    private suspend fun refreshSubscriptionAndConnect() {
        try {
            val groups = SagerDatabase.groupDao.allGroups()
            val sub = groups.firstOrNull { it.type == GroupType.SUBSCRIPTION }
            if (sub != null) {
                GroupUpdater.startUpdate(sub, true)
                // Wait a bit for refresh to complete
                kotlinx.coroutines.delay(3000)
                val profiles = SagerDatabase.proxyDao.getByGroup(sub.id)
                val first = profiles.firstOrNull()
                if (first != null) {
                    DataStore.selectedProxy = first.id
                    onMainDispatcher { connect.launch(null) }
                } else {
                    onMainDispatcher {
                        snackbar("Не удалось загрузить серверы. Попробуйте «Обновить подключение» в меню.").show()
                    }
                }
            }
        } catch (e: Exception) {
            onMainDispatcher {
                snackbar("Ошибка обновления: ${e.message}").show()
            }
        }
    }

    private fun fetchExternalIp() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Wait for VPN tunnel to fully initialize before making the request
            kotlinx.coroutines.delay(2000)
            
            var success = false
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
                    if (ip.isNotEmpty()) {
                        val flag = countryCodeToFlag(cc)
                        withContext(Dispatchers.Main) {
                            binding.tvIpInfo.text = "IP: $ip"
                            binding.tvFlag.text = flag
                            binding.tvCountryCode.text = cc
                            binding.ipPill.visibility = View.VISIBLE
                        }
                        success = true
                        break
                    }
                } catch (_: Exception) { }
                
                // If first attempt failed, wait and retry
                if (!success && attempt < 2) {
                    kotlinx.coroutines.delay(3000)
                }
            }
        }
    }

    private fun countryCodeToFlag(cc: String): String {
        if (cc.length != 2) return ""
        val first = Character.toChars(0x1F1E6 - 'A'.code + cc[0].uppercaseChar().code)
        val second = Character.toChars(0x1F1E6 - 'A'.code + cc[1].uppercaseChar().code)
        return String(first) + String(second)
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
        restoreMobileSessionStats()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        saveMobileSessionStats()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session stats persistence (survive background transitions)
    // ─────────────────────────────────────────────────────────────────────────

    private fun saveMobileSessionStats() {
        if (connectTime == 0L) return
        getSharedPreferences("lvovflow_mobile_session", MODE_PRIVATE).edit().apply {
            putLong("connect_time", connectTime)
            apply()
        }
    }

    private fun restoreMobileSessionStats() {
        val prefs = getSharedPreferences("lvovflow_mobile_session", MODE_PRIVATE)
        val savedConnectTime = prefs.getLong("connect_time", 0L)
        if (savedConnectTime > 0L) {
            connectTime = savedConnectTime
            wasConnected = true
        }
    }

    private fun clearMobileSessionStats() {
        connectTime = 0L
        getSharedPreferences("lvovflow_mobile_session", MODE_PRIVATE).edit().clear().apply()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (super.onKeyDown(keyCode, event)) return true

        val fragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

    private fun checkAppUpdate() {
        runOnDefaultDispatcher {
            try {
                val prefs = getSharedPreferences("lvovflow", android.content.Context.MODE_PRIVATE)
                val email = prefs.getString("user_email", "") ?: ""
                val deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown_device"
                val model = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                
                val url = java.net.URL("https://lvovflow.com/api/app/app_sync.php")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doOutput = true
                
                val jsonPayload = JSONObject().apply {
                    put("email", email)
                    put("device_id", deviceId)
                    put("device_model", model)
                    put("app_version", BuildConfig.VERSION_NAME)
                }.toString()

                java.io.OutputStreamWriter(connection.outputStream).use { it.write(jsonPayload) }
                
                if (connection.responseCode != 200) return@runOnDefaultDispatcher
                
                val content = connection.inputStream.bufferedReader().readText()
                if (content.isBlank()) return@runOnDefaultDispatcher

                val response = JSONObject(content)
                
                // 0a. Check remote logout (device was removed from cabinet)
                if (!response.optBoolean("ok", true) && response.optBoolean("logged_out", false)) {
                    onMainDispatcher {
                        // Stop VPN on THIS device immediately
                        if (DataStore.serviceState.started) {
                            SagerNet.stopService()
                        }
                        // Clear all session data
                        prefs.edit().clear().apply()
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle("Выход с устройства")
                            .setMessage("Это устройство было удалено из вашего аккаунта. Войдите снова для продолжения.")
                            .setPositiveButton("Войти") { _, _ ->
                                startActivity(android.content.Intent(this@MainActivity, ActivationActivity::class.java))
                                finishAffinity()
                            }
                            .setCancelable(false)
                            .show()
                    }
                    return@runOnDefaultDispatcher
                }

                // 0b. Check device limit rejection — force user to choose
                if (!response.optBoolean("ok", true) && response.optString("error") == "device_limit") {
                    onMainDispatcher {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle("⚠️ Достигнут лимит устройств")
                            .setMessage("Ваша подписка позволяет использовать не более 2 устройств одновременно.\n\nЧтобы добавить это устройство, улучшите подписку или выйдите с другого устройства в личном кабинете.")
                            .setCancelable(false)
                            .setPositiveButton("Улучшить подписку") { _, _ ->
                                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://lvovflow.com/cabinet.php")))
                                finishAffinity()
                            }
                            .setNegativeButton("Выйти с устройства") { _, _ ->
                                prefs.edit().clear().apply()
                                startActivity(android.content.Intent(this@MainActivity, ActivationActivity::class.java))
                                finishAffinity()
                            }
                            .show()
                    }
                    return@runOnDefaultDispatcher
                }

                // 1. Process Update
                if (response.has("update") && !response.isNull("update")) {
                    val updateObj = response.getJSONObject("update")
                    val serverVersionName = updateObj.optString("versionName", "")
                    val rawUrl = updateObj.optString("url", "https://lvovflow.com/app/LvovFlow-latest-{abi}.apk")
                    val deviceAbi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                    val releaseUrl = rawUrl.replace("{abi}", deviceAbi)
                    val changelog = updateObj.optString("changelog", "Оптимизация скорости и повышение стабильности работы.")
                    
                    if (serverVersionName.isNotBlank() && serverVersionName != BuildConfig.VERSION_NAME) {
                        onMainDispatcher {
                            showUpdateDialog(serverVersionName, changelog, releaseUrl)
                        }
                        return@runOnDefaultDispatcher // Don't stack notifications with update dialog
                    }
                }
                
                // 2. Process Notifications Array
                if (response.has("notifications")) {
                    val notifsArr = response.getJSONArray("notifications")
                    prefs.edit().putString("notifications_json", notifsArr.toString()).apply()
                    
                    if (notifsArr.length() > 0) {
                        val latestId = notifsArr.getJSONObject(0).optInt("id", 0)
                        prefs.edit().putInt("pending_notif_id", latestId).apply()
                    }
                    onMainDispatcher {
                        updateChatIconColor()
                    }
                }
            } catch (e: Exception) {
                // Ignore silent update/sync errors
            }
        }
    }

    private fun showUpdateDialog(version: String, changelog: String, url: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_update, null)
        val titleView = dialogView.findViewById<android.widget.TextView>(R.id.update_title)
        val descView = dialogView.findViewById<android.widget.TextView>(R.id.update_desc)
        val btnUpdate = dialogView.findViewById<android.widget.TextView>(R.id.btn_update)
        val btnCancel = dialogView.findViewById<android.widget.TextView>(R.id.btn_cancel)

        titleView.text = "Новая версия: $version"
        descView.text = changelog

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnUpdate.setOnClickListener {
            // LvovFlow: Stop VPN before installing update to prevent broken state
            if (DataStore.serviceState == BaseService.State.Connected ||
                DataStore.serviceState == BaseService.State.Connecting) {
                SagerNet.stopService()
                // Small delay to let VPN service stop cleanly
                lifecycleScope.launch {
                    delay(500)
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        updateChatIconColor()
    }

    fun onChatClicked(v: android.view.View?) {
        startActivity(Intent(this, NotificationsActivity::class.java))
    }

    private fun updateChatIconColor() {
        val badge = findViewById<android.view.View>(R.id.notification_badge) ?: return
        val prefs = getSharedPreferences("lvovflow", android.content.Context.MODE_PRIVATE)
        val pendingId = prefs.getInt("pending_notif_id", 0)
        val lastId = prefs.getInt("last_notif_id", 0)

        badge.visibility = if (pendingId > lastId) android.view.View.VISIBLE else android.view.View.GONE
    }

    // ── LvovFlow: Premium "Lion Heartbeat" haptic feedback ──────────────
    @Suppress("DEPRECATION")
    private fun performLionHaptic(light: Boolean) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                mgr.defaultVibrator
            } else {
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }

            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Double-pulse "heartbeat" pattern
                // timings:  wait, vib1, pause, vib2
                // amplitudes: 0,  low,  0,    medium
                val timings: LongArray
                val amplitudes: IntArray
                if (light) {
                    // Light tap — button press
                    timings    = longArrayOf(0, 30, 50, 40)
                    amplitudes = intArrayOf(0, 40, 0, 80)
                } else {
                    // Strong tap — successful connection
                    timings    = longArrayOf(0, 40, 60, 50)
                    amplitudes = intArrayOf(0, 80, 0, 140)
                }
                vibrator.vibrate(
                    VibrationEffect.createWaveform(timings, amplitudes, -1)
                )
            } else {
                // Fallback for older devices (< API 26)
                val duration = if (light) 50L else 100L
                vibrator.vibrate(duration)
            }
        } catch (_: Exception) {
            // Silently ignore — haptics are non-critical
        }
    }
}
