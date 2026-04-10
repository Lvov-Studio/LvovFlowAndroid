package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import android.view.LayoutInflater
import android.view.ViewGroup
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.GroupType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LvovFlow — Profile Screen Fragment
 * Accessible via bottom navigation "Профиль" tab.
 */
class ProfileFragment : ToolbarFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Toolbar expected by ToolbarFragment (invisible in layout)
        toolbar.navigationIcon = null
        toolbar.title = null

        val prefs = requireContext().getSharedPreferences("lvovflow", Context.MODE_PRIVATE)
        val email     = prefs.getString("user_email", "") ?: ""
        val expireDate = prefs.getString("expire_date", "") ?: ""

        // LvovFlow: mask email for privacy — show first 3 chars + ***
        val maskedEmail = if (email.contains("@")) {
            val local = email.substringBefore("@")
            val domain = email.substringAfter("@")
            val visible = local.take(3)
            "${visible}***@${domain}"
        } else email
        view.findViewById<TextView>(R.id.tv_email).text = maskedEmail
        // Set profile avatar letter
        val profileLetter = if (email.isNotBlank()) email.substring(0, 1).uppercase() else "L"
        view.findViewById<TextView>(R.id.tv_profile_letter)?.text = profileLetter
        view.findViewById<TextView>(R.id.tv_expire).text =
            if (expireDate.isNotBlank()) "Подписка до $expireDate" else "Активная подписка"

        // Subscription sub-status row with green/red dot
        val tvSubStatus = view.findViewById<TextView>(R.id.tv_sub_status)
        val subDot = view.findViewById<android.view.View>(R.id.sub_status_dot)
        val isExpired = prefs.getBoolean("is_expired", false)
        if (isExpired) {
            tvSubStatus.text = "Истекла"
            tvSubStatus.setTextColor(0xFFEF4444.toInt())
            subDot.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFEF4444.toInt())
        } else {
            tvSubStatus.text = if (expireDate.isNotBlank()) "Активна до $expireDate" else "Активна"
            tvSubStatus.setTextColor(0xFF22C55E.toInt())
            subDot.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF22C55E.toInt())
        }

        // Version
        view.findViewById<TextView>(R.id.tv_version).text =
            "LvovFlow v${BuildConfig.VERSION_NAME}"

        // Check updates
        view.findViewById<TextView>(R.id.tv_check_update)?.setOnClickListener {
            Toast.makeText(requireContext(), "Проверка обновлений…", Toast.LENGTH_SHORT).show()
            // Triggers the in-app update check from MainActivity
            (requireActivity() as? MainActivity)?.let { main ->
                try {
                    val method = main.javaClass.getDeclaredMethod("checkForUpdates")
                    method.isAccessible = true
                    method.invoke(main)
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), "✅ У вас последняя версия", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Menu items
        view.findViewById<LinearLayout>(R.id.item_refresh).setOnClickListener {
            refreshSubscription()
        }

        view.findViewById<LinearLayout>(R.id.item_subscription).setOnClickListener {
            startActivity(Intent(requireContext(), SubscriptionActivity::class.java))
        }

        view.findViewById<LinearLayout>(R.id.item_support).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/lvovflow_support_bot")))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "@lvovflow_support_bot", Toast.LENGTH_SHORT).show()
            }
        }

        // Привилегии (referral + promo combined screen)
        view.findViewById<android.widget.LinearLayout>(R.id.item_privileges).setOnClickListener {
            (requireActivity() as? MainActivity)?.displayFragment(PrivilegesFragment())
        }

        view.findViewById<LinearLayout>(R.id.item_devices).setOnClickListener {
            startActivity(Intent(requireContext(), DevicesActivity::class.java))
        }

        view.findViewById<LinearLayout>(R.id.item_settings).setOnClickListener {
            (requireActivity() as? MainActivity)?.displayFragment(SettingsFragment())
        }

        view.findViewById<View>(R.id.item_logout).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Выйти из аккаунта?")
                .setMessage("Вы будете перенаправлены на экран входа.")
                .setPositiveButton("Выйти") { _, _ -> logout() }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun refreshSubscription() {
        Toast.makeText(requireContext(), "Обновление серверов…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val groups = withContext(Dispatchers.IO) {
                    SagerDatabase.groupDao.allGroups()
                }
                val sub = groups.firstOrNull { it.type == GroupType.SUBSCRIPTION } ?: run {
                    Toast.makeText(requireContext(), "Подписка не найдена", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    GroupManager.updateGroup(sub)
                }
                Toast.makeText(requireContext(), "✅ Серверы обновлены", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Ошибка обновления", Toast.LENGTH_SHORT).show()
            }
        }
    }



    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun logout() {
        requireContext().getSharedPreferences("lvovflow", Context.MODE_PRIVATE).edit().clear().apply()
        
        // Clear all proxies and groups from local DB so the next login starts fresh
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            val allGroups = io.nekohasekai.sagernet.database.SagerDatabase.groupDao.allGroups()
            if (allGroups.isNotEmpty()) {
                io.nekohasekai.sagernet.database.GroupManager.deleteGroup(allGroups)
            }
        }

        startActivity(Intent(requireContext(), ActivationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        requireActivity().finish()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
