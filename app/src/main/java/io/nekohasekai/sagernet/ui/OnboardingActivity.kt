package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.nekohasekai.sagernet.R

class OnboardingActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var dotsContainer: LinearLayout
    private lateinit var btnNext: TextView
    private lateinit var btnSkip: TextView

    data class OnboardingPage(
        val iconRes: Int,
        val title: String,
        val description: String,
        val logo1: Int = 0,
        val logo2: Int = 0,
        val logo3: Int = 0
    )

    private val pages = listOf(
        OnboardingPage(
            iconRes = R.drawable.ic_onboarding_speed,
            title = "Молниеносная скорость",
            description = "Видео, стримы и загрузки без лагов и ограничений. Максимальная скорость для любого контента.",
            logo1 = R.drawable.ic_logo_youtube,
            logo2 = R.drawable.ic_logo_instagram,
            logo3 = R.drawable.ic_logo_tiktok
        ),
        OnboardingPage(
            iconRes = R.drawable.ic_onboarding_bank,
            title = "Включил и забыл",
            description = "Банки, доставка, госуслуги — всё работает без переключений. Не нужно ничего отключать.",
            logo1 = R.drawable.ic_logo_sber,
            logo2 = R.drawable.ic_logo_tinkoff,
            logo3 = 0
        ),
        OnboardingPage(
            iconRes = R.drawable.ic_shield_lock,
            title = "Полная приватность",
            description = "Надёжное шифрование трафика и доступ ко всему контенту. Твой интернет — твои правила."
        ),
        OnboardingPage(
            iconRes = R.drawable.ic_ai_sparkle,
            title = "ИИ без границ",
            description = "Все нейросети в одном месте. Общайся с лучшими ИИ напрямую из приложения.",
            logo1 = R.drawable.ic_logo_gemini,
            logo2 = R.drawable.ic_logo_chatgpt,
            logo3 = R.drawable.ic_logo_claude
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        window.statusBarColor = 0xFF0A1628.toInt()
        window.navigationBarColor = 0xFF0A1628.toInt()

        pager = findViewById(R.id.onboarding_pager)
        dotsContainer = findViewById(R.id.dots_container)
        btnNext = findViewById(R.id.btn_next)
        btnSkip = findViewById(R.id.btn_skip)

        pager.adapter = OnboardingAdapter(pages)
        setupDots()

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
                if (position == pages.size - 1) {
                    btnNext.text = "Начать"
                    btnSkip.visibility = View.GONE
                } else {
                    btnNext.text = "Далее"
                    btnSkip.visibility = View.VISIBLE
                }
            }
        })

        btnNext.setOnClickListener {
            if (pager.currentItem < pages.size - 1) {
                pager.currentItem += 1
            } else {
                finishOnboarding()
            }
        }

        btnSkip.setOnClickListener {
            finishOnboarding()
        }
    }

    private fun finishOnboarding() {
        getSharedPreferences("lvovflow", MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_shown", true)
            .apply()
        finish()
    }

    private fun setupDots() {
        dotsContainer.removeAllViews()
        for (i in pages.indices) {
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(8.dp, 8.dp).apply {
                    marginEnd = if (i < pages.size - 1) 10.dp else 0
                }
                setBackgroundResource(R.drawable.bg_circle_green)
                backgroundTintList = ContextCompat.getColorStateList(
                    this@OnboardingActivity,
                    if (i == 0) R.color.onboarding_dot_active else R.color.onboarding_dot_inactive
                )
            }
            dotsContainer.addView(dot)
        }
    }

    private fun updateDots(active: Int) {
        for (i in 0 until dotsContainer.childCount) {
            dotsContainer.getChildAt(i).backgroundTintList = ContextCompat.getColorStateList(
                this,
                if (i == active) R.color.onboarding_dot_active else R.color.onboarding_dot_inactive
            )
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    // Adapter
    class OnboardingAdapter(private val pages: List<OnboardingPage>) :
        RecyclerView.Adapter<OnboardingAdapter.PageViewHolder>() {

        class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.page_icon)
            val title: TextView = view.findViewById(R.id.page_title)
            val description: TextView = view.findViewById(R.id.page_description)
            val logosRow: LinearLayout = view.findViewById(R.id.logos_row)
            val logo1: ImageView = view.findViewById(R.id.logo_1)
            val logo2: ImageView = view.findViewById(R.id.logo_2)
            val logo3: ImageView = view.findViewById(R.id.logo_3)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_onboarding_page, parent, false)
            return PageViewHolder(view)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            val page = pages[position]
            holder.icon.setImageResource(page.iconRes)
            holder.title.text = page.title
            holder.description.text = page.description

            if (page.logo1 != 0) {
                holder.logosRow.visibility = View.VISIBLE
                holder.logo1.setImageResource(page.logo1)
                if (page.logo2 != 0) {
                    holder.logo2.setImageResource(page.logo2)
                    holder.logo2.visibility = View.VISIBLE
                } else {
                    holder.logo2.visibility = View.GONE
                }
                if (page.logo3 != 0) {
                    holder.logo3.setImageResource(page.logo3)
                    holder.logo3.visibility = View.VISIBLE
                } else {
                    holder.logo3.visibility = View.GONE
                }
            } else {
                holder.logosRow.visibility = View.GONE
            }
        }

        override fun getItemCount() = pages.size
    }
}
