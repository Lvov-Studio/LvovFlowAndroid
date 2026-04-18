package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutBypassAppItemBinding
import io.nekohasekai.sagernet.databinding.LayoutBypassAppsBinding
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/**
 * LvovFlow: Split-Tunneling управление.
 * Экран состоит из двух секций:
 *  1. "Авто-исключения" — встроенный список RU-приложений (smartBypassRu)
 *  2. "Дополнительные исключения" — любые установленные приложения (DataStore.individual)
 */
class BypassAppsActivity : ThemedActivity() {

    companion object {
        // Авто-список (синхронизирован с VpnService.kt ruBypassPackages)
        val AUTO_BYPASS_PACKAGES = listOf(
            "com.wildberries.ru"        to "Wildberries",
            "ru.ozon.app.android"       to "Ozon",
            "ru.sberbankmobile"         to "СберБанк",
            "com.idamob.tinkoff.android" to "Т-Банк",
            "ru.alfabank.mobile.android" to "Альфа-Банк",
            "ru.vtb24.mobilebank.android" to "ВТБ",
            "ru.yandex.yandexmaps"      to "Яндекс Карты",
            "com.yandex.market"         to "Яндекс Маркет",
            "ru.yandex.taxi"            to "Яндекс Такси",
            "ru.avito.android"          to "Авито",
            "com.vkontakte.android"     to "ВКонтакте",
            "ru.ok.android"             to "Одноклассники",
            "ru.gosuslugi.pos"          to "Госуслуги",
            "ru.oneme.app"              to "MAX Мессенджер",
            "ru.megafon.mlk"            to "МегаФон",
            "ru.beeline.services"       to "Билайн",
            "ru.mts.mtsmon"             to "МТС"
        )
        private val AUTO_BYPASS_PKG_SET = AUTO_BYPASS_PACKAGES.map { it.first }.toSet()
    }

    // --- Data classes ---

    sealed class ListItem {
        data class Header(val titleResId: Int, val subtitleResId: Int? = null) : ListItem()
        data class AppItem(
            val packageName: String,
            val appName: String,
            val icon: Drawable?,
            val isAutoBypass: Boolean,   // true = авто-список, нельзя снять
            var isChecked: Boolean
        ) : ListItem()
    }

    // --- State ---
    private lateinit var binding: LayoutBypassAppsBinding
    private val allItems = mutableListOf<ListItem>()      // полный список (авто + ручные)
    private val filteredItems = mutableListOf<ListItem>() // после поиска
    private val manualBypassSet = mutableSetOf<String>()  // DataStore.individual

    private val adapter = BypassAdapter()
    private var loadJob: Job? = null

    // --- Lifecycle ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutBypassAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        // Читаем текущий ручной список
        manualBypassSet.addAll(
            DataStore.individual.split('\n').filter { it.isNotBlank() }
        )

        // Поиск
        binding.search.addTextChangedListener { text ->
            applyFilter(text?.toString() ?: "")
        }

        loadApps()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.bypass_apps_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_clear_manual -> {
                manualBypassSet.clear()
                saveManualList()
                loadApps()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun supportNavigateUpTo(upIntent: Intent) =
        super.supportNavigateUpTo(upIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))

    // --- Data loading ---

    private fun loadApps() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launchWhenCreated {
            binding.loading.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE

            withContext(Dispatchers.IO) {
                buildItemList()
            }

            applyFilter(binding.search.text?.toString() ?: "")
            binding.loading.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
    }

    private fun buildItemList() {
        allItems.clear()
        val pm = packageManager

        // --- Секция 1: Авто-исключения ---
        allItems.add(ListItem.Header(R.string.bypass_apps_auto_section, R.string.bypass_apps_auto_desc))
        for ((pkg, fallbackName) in AUTO_BYPASS_PACKAGES) {
            val icon = try { pm.getApplicationIcon(pkg) } catch (e: PackageManager.NameNotFoundException) { null }
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: PackageManager.NameNotFoundException) { fallbackName }

            allItems.add(
                ListItem.AppItem(
                    packageName = pkg,
                    appName = label,
                    icon = icon,
                    isAutoBypass = true,
                    isChecked = true   // всегда включено (управляется smartBypassRu)
                )
            )
        }

        // --- Секция 2: Ручные исключения ---
        allItems.add(ListItem.Header(R.string.bypass_apps_manual_section))

        val installedApps = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    app.packageName != packageName &&
                    app.packageName !in AUTO_BYPASS_PKG_SET &&
                    pm.getLaunchIntentForPackage(app.packageName) != null // только приложения с иконкой
                }
                .sortedWith(compareBy(
                    { app -> manualBypassSet.contains(app.packageName).not() }, // выбранные наверху
                    { app -> pm.getApplicationLabel(app).toString() }
                ))
        } catch (e: Exception) {
            Logs.w(e)
            emptyList()
        }

        for (appInfo in installedApps) {
            val icon = try { pm.getApplicationIcon(appInfo.packageName) } catch (e: Exception) { null }
            val label = pm.getApplicationLabel(appInfo).toString()
            allItems.add(
                ListItem.AppItem(
                    packageName = appInfo.packageName,
                    appName = label,
                    icon = icon,
                    isAutoBypass = false,
                    isChecked = manualBypassSet.contains(appInfo.packageName)
                )
            )
        }
    }

    // --- Filter ---

    private fun applyFilter(query: String) {
        filteredItems.clear()
        if (query.isBlank()) {
            filteredItems.addAll(allItems)
        } else {
            val q = query.lowercase()
            var lastHeaderAdded = false
            var lastHeader: ListItem.Header? = null

            for (item in allItems) {
                when (item) {
                    is ListItem.Header -> {
                        lastHeader = item
                        lastHeaderAdded = false
                    }
                    is ListItem.AppItem -> {
                        if (item.appName.lowercase().contains(q) ||
                            item.packageName.lowercase().contains(q)
                        ) {
                            if (!lastHeaderAdded && lastHeader != null) {
                                filteredItems.add(lastHeader)
                                lastHeaderAdded = true
                            }
                            filteredItems.add(item)
                        }
                    }
                }
            }
        }
        adapter.notifyDataSetChanged()
    }

    // --- DataStore save ---

    private fun saveManualList() {
        DataStore.individual = manualBypassSet.joinToString("\n")
        // Если список не пустой — убеждаемся что proxyApps=true и bypass=true
        if (manualBypassSet.isNotEmpty()) {
            DataStore.proxyApps = true
            DataStore.bypass = true
        }
        // Применяем без перезапуска если VPN запущен
        SagerNet.reloadService()
    }

    // --- Adapter ---

    private val VIEWTYPE_HEADER = 0
    private val VIEWTYPE_APP = 1

    inner class BypassAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        inner class HeaderVH(val binding: LayoutBypassAppItemBinding) : RecyclerView.ViewHolder(binding.root)
        inner class AppVH(val binding: LayoutBypassAppItemBinding) : RecyclerView.ViewHolder(binding.root)

        override fun getItemViewType(position: Int) =
            if (filteredItems[position] is ListItem.Header) VIEWTYPE_HEADER else VIEWTYPE_APP

        override fun getItemCount() = filteredItems.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val b = LayoutBypassAppItemBinding.inflate(layoutInflater, parent, false)
            return if (viewType == VIEWTYPE_HEADER) HeaderVH(b) else AppVH(b)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = filteredItems[position]) {
                is ListItem.Header -> {
                    val b = (holder as HeaderVH).binding
                    b.appSwitch.visibility = View.GONE
                    b.sectionLabel.visibility = View.GONE
                    b.appIcon.visibility = View.GONE
                    b.appName.text = getString(item.titleResId)
                    b.appName.textSize = 13f
                    b.appName.alpha = 0.6f
                    b.appPackage.text = if (item.subtitleResId != null) getString(item.subtitleResId) else ""
                    b.root.isClickable = false
                    b.root.isFocusable = false
                }
                is ListItem.AppItem -> {
                    val b = (holder as AppVH).binding
                    b.appIcon.visibility = View.VISIBLE
                    b.appName.textSize = 15f
                    b.appName.alpha = 1f
                    b.appName.text = item.appName
                    b.appPackage.text = item.packageName
                    b.appIcon.setImageDrawable(item.icon)
                    b.sectionLabel.visibility = View.GONE

                    if (item.isAutoBypass) {
                        // Авто-список: Switch залочен в ON
                        b.appSwitch.visibility = View.VISIBLE
                        b.appSwitch.isChecked = true
                        b.appSwitch.isEnabled = false
                        b.root.isClickable = false
                        b.root.isFocusable = false
                    } else {
                        // Ручной: Switch интерактивный
                        b.appSwitch.visibility = View.VISIBLE
                        b.appSwitch.isEnabled = true
                        // Отключаем собственную обработку кликов у Switch —
                        // иначе Switch поглощает все касания и root.setOnClickListener не срабатывает
                        b.appSwitch.isClickable = false
                        b.appSwitch.isFocusable = false
                        // Сначала убрать listener, потом установить значение
                        b.appSwitch.setOnCheckedChangeListener(null)
                        b.root.setOnClickListener(null)
                        b.appSwitch.isChecked = item.isChecked
                        b.root.isClickable = true
                        b.root.isFocusable = true
                        b.root.setOnClickListener {
                            val newState = !item.isChecked
                            item.isChecked = newState
                            b.appSwitch.isChecked = newState
                            if (newState) manualBypassSet.add(item.packageName)
                            else manualBypassSet.remove(item.packageName)
                            saveManualList()
                        }
                    }
                }
            }
        }
    }
}
