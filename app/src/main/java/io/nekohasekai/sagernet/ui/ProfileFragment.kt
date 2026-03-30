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
        view.findViewById<TextView>(R.id.tv_expire).text =
            if (expireDate.isNotBlank()) "Подписка до $expireDate" else "Активная подписка"

        // Subscription sub-status row
        val tvSubStatus = view.findViewById<TextView>(R.id.tv_sub_status)
        tvSubStatus.text = if (expireDate.isNotBlank()) "Активна до $expireDate" else "Активна"

        // Version
        view.findViewById<TextView>(R.id.tv_version).text =
            "LvovFlow v${BuildConfig.VERSION_NAME}"

        // Menu items
        view.findViewById<LinearLayout>(R.id.item_refresh).setOnClickListener {
            refreshSubscription()
        }

        view.findViewById<LinearLayout>(R.id.item_subscription).setOnClickListener {
            val msg = if (expireDate.isNotBlank())
                "Ваша подписка активна до $expireDate.\n\nДля продления свяжитесь с поддержкой или введите промокод."
            else
                "Ваша подписка активна."
            AlertDialog.Builder(requireContext())
                .setTitle("⭐ Подписка")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        }

        view.findViewById<LinearLayout>(R.id.item_support).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/LvovFlowBot")))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "@LvovFlowBot", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<LinearLayout>(R.id.item_promo).setOnClickListener {
            showPromoDialog()
        }

        view.findViewById<LinearLayout>(R.id.item_logout).setOnClickListener {
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

    private fun showPromoDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Введите промокод"
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("🎟 Промокод")
            .setView(input)
            .setPositiveButton("Применить") { _, _ ->
                val code = input.text.toString().trim().uppercase()
                applyPromo(code)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun applyPromo(code: String) {
        if (code.isBlank()) return
        Toast.makeText(requireContext(), "Промокод «$code» принят! Свяжитесь с поддержкой для активации.", Toast.LENGTH_LONG).show()
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
