package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
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
 * LvovFlow — Profile Screen Fragment (Concept v2 Design)
 * Matches vpn_concept.html profile tab:
 * - Avatar + Name + Email + ID badge
 * - Stats row (Downloaded / Uploaded)
 * - Glassmorphism settings groups
 * - Logout button with icon
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

        // ── Avatar + User Info ──
        val email = prefs.getString("user_email", "") ?: ""
        val maskedEmail = maskEmail(email)
        val firstLetter = if (email.isNotBlank()) {
            val firstChar = email.first().uppercaseChar()
            // If first char is a digit, try to use something friendlier
            if (firstChar.isLetter()) firstChar.toString() else "U"
        } else "U"

        view.findViewById<TextView>(R.id.tv_avatar_letter).text = firstLetter
        view.findViewById<TextView>(R.id.tv_user_name)?.text = generateDisplayName(email)
        view.findViewById<TextView>(R.id.tv_user_email)?.text = maskedEmail

        // Generate a user ID from session token
        val sessionToken = prefs.getString("session_token", "") ?: ""
        val userId = if (sessionToken.length >= 8) {
            val hash = sessionToken.takeLast(6).uppercase()
            "ID: ${hash.take(4)}-${hash.takeLast(2)}X"
        } else "ID: 0000-XXX"
        view.findViewById<TextView>(R.id.tv_user_id)?.text = userId

        // ── Stats Row ──
        // Use session stats from SharedPreferences (written by MainActivity)
        val totalDown = prefs.getLong("total_download", 0L)
        val totalUp = prefs.getLong("total_upload", 0L)
        view.findViewById<TextView>(R.id.tv_stat_download)?.text = formatBytes(totalDown)
        view.findViewById<TextView>(R.id.tv_stat_upload)?.text = formatBytes(totalUp)

        // ── Subscription status ──
        val expireDate = prefs.getString("expire_date", "") ?: ""
        val subStatus = view.findViewById<TextView>(R.id.tv_sub_status)
        if (expireDate.isNotBlank()) {
            subStatus?.text = "Активна"
            subStatus?.setTextColor(0xFF00E676.toInt())
        } else {
            subStatus?.text = "—"
            subStatus?.setTextColor(0xFF8B949E.toInt())
        }

        // Version
        view.findViewById<TextView>(R.id.tv_version).text =
            "LvovFlow v${BuildConfig.VERSION_NAME}"

        // Check updates
        view.findViewById<TextView>(R.id.tv_check_update)?.setOnClickListener {
            Toast.makeText(requireContext(), "Проверка обновлений…", Toast.LENGTH_SHORT).show()
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

        // ── Menu items ──
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
        view.findViewById<LinearLayout>(R.id.item_privileges).setOnClickListener {
            (requireActivity() as? MainActivity)?.displayFragment(PrivilegesFragment())
        }

        view.findViewById<LinearLayout>(R.id.item_devices).setOnClickListener {
            startActivity(Intent(requireContext(), DevicesActivity::class.java))
        }

        view.findViewById<LinearLayout>(R.id.item_settings).setOnClickListener {
            (requireActivity() as? MainActivity)?.displayFragmentWithId(R.id.nav_settings)
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

    private fun maskEmail(email: String): String {
        if (email.isBlank() || !email.contains("@")) return email
        val parts = email.split("@")
        val name = parts[0]
        val domain = parts[1]
        val visible = if (name.length > 3) name.take(3) else name.take(1)
        return "$visible***@$domain"
    }

    private fun generateDisplayName(email: String): String {
        if (email.isBlank()) return "Пользователь"
        val name = email.substringBefore("@")
        // If >50% of the name chars are digits, don't show it
        val digitRatio = name.count { it.isDigit() }.toFloat() / name.length.coerceAtLeast(1)
        return if (digitRatio > 0.5f) {
            "Пользователь"
        } else {
            name.replaceFirstChar { it.uppercase() }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes Б"
            bytes < 1024 * 1024 -> String.format("%.1f КБ", bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> String.format("%.1f МБ", bytes / (1024.0 * 1024))
            else -> String.format("%.1f ГБ", bytes / (1024.0 * 1024 * 1024))
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
