package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.preference.*
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager

/**
 * LvovFlow: Simplified settings fragment — only Smart Bypass toggle.
 * Replaces the original SettingsPreferenceFragment for the in-app Settings screen.
 */
class LvovFlowSettingsFragment : PreferenceFragmentCompat() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.layoutManager = FixedLinearLayoutManager(listView)
    }

    private val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        addPreferencesFromResource(R.xml.lvovflow_settings)

        val smartBypassRu = findPreference<SwitchPreference>(Key.SMART_BYPASS_RU)!!
        smartBypassRu.onPreferenceChangeListener = reloadListener
    }
}
