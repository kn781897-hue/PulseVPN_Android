package com.nikolay.myvpn

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MenuActivity : AppCompatActivity() {

    // Ссылки на View
    private lateinit var tvUserTitle: TextView
    private lateinit var tvUserSub: TextView
    private lateinit var tvBadge: TextView

    private lateinit var vAvatarBg: View
    private lateinit var ivAvatarIcon: ImageView
    private lateinit var btnProfileCard: View

    // Элементы кнопки Премиум
    private lateinit var btnPremium: View
    private lateinit var ivPremIcon: ImageView
    private lateinit var tvPremTitle: TextView
    private lateinit var tvPremSub: TextView
    private lateinit var ivArrowPrem: ImageView

    // Заголовки
    private lateinit var tvMenuTitle: TextView
    private lateinit var btnClose: ImageButton

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    private var premiumListener: ListenerRegistration? = null

    // === НОВЫЕ ПЕРЕМЕННЫЕ ДЛЯ ХРАНЕНИЯ СТАТУСА ===
    private var isUserPremium = false
    private var userExpiryDate: Long = 0

    override fun onDestroy() {
        super.onDestroy()
        premiumListener?.remove()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        // Инициализация
        btnProfileCard = findViewById(R.id.btnLogin)
        tvUserTitle = findViewById(R.id.tvUserTitle)
        tvUserSub = findViewById(R.id.tvUserSub)
        tvBadge = findViewById(R.id.tvBadge)
        vAvatarBg = findViewById(R.id.vAvatar)
        ivAvatarIcon = findViewById(R.id.ivAvatarIcon)

        btnPremium = findViewById(R.id.btnPremium)
        ivPremIcon = findViewById(R.id.ivPremIcon)
        tvPremTitle = findViewById(R.id.tvPremTitle)
        tvPremSub = findViewById(R.id.tvPremSub)
        ivArrowPrem = findViewById(R.id.ivPremIcon)

        tvMenuTitle = findViewById(R.id.tvMenuTitle)
        btnClose = findViewById(R.id.btnClose)

        btnClose.setOnClickListener { finish() }

        // === ИЗМЕНЕННАЯ ЛОГИКА КНОПКИ PREMIUM ===
        btnPremium.setOnClickListener {
            if (isUserPremium) {
                // Если подписка активна -> Показываем инфо
                showSubscriptionInfo()
            } else {
                // Если нет -> Идем покупать
                startActivity(Intent(this, PremiumActivity::class.java))
            }
        }

        // Кнопки меню
        setupMenuItem(R.id.btnTelegram, "Telegram Channel", R.drawable.ic_telegram) {
            openUrl("https://t.me/telegram")
        }
        setupMenuItem(R.id.btnRate, "Rate Application", R.drawable.ic_star) {
            val packageName = packageName
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
            } catch (e: Exception) {
                openUrl("https://play.google.com/store/apps/details?id=$packageName")
            }
        }
        setupMenuItem(R.id.btnShare, "Share with Friends", R.drawable.ic_share) {
            try {
                val intent = Intent(Intent.ACTION_SEND)
                intent.type = "text/plain"
                intent.putExtra(Intent.EXTRA_TEXT, "Download Pulse VPN: https://play.google.com/store/apps/details?id=$packageName")
                startActivity(Intent.createChooser(intent, "Share via"))
            } catch (e: Exception) {}
        }
        setupMenuItem(R.id.btnAbout, "About & Policy", R.drawable.ic_info) {
            openUrl("https://telegra.ph/Dokument-1-Politika-konfidencialnosti-Privacy-Policy-01-10")
        }
    }

    override fun onResume() {
        super.onResume()
        updateScreenTheme()
        updateUI()
    }

    private fun updateScreenTheme() {
        ThemeManager.applyTheme(this)
        ThemeManager.applyTextColors(this, tvMenuTitle, tvUserTitle, tvPremTitle)
        ThemeManager.applySubTextColors(this, tvUserSub, tvPremSub)
        ThemeManager.applyBtnColors(this, btnClose)
        ThemeManager.applyIconColors(this, ivArrowPrem)
        ThemeManager.applyGlassColors(this,
            findViewById(R.id.btnTelegram), findViewById(R.id.btnRate),
            findViewById(R.id.btnShare), findViewById(R.id.btnAbout)
        )
        refreshListColors()
    }

    private fun refreshListColors() {
        val isDark = ThemeManager.isDarkMode(this)
        val color = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        listOf(R.id.btnTelegram, R.id.btnRate, R.id.btnShare, R.id.btnAbout).forEach { id ->
            val container = findViewById<View>(id)
            container.findViewById<TextView>(R.id.itemTitle).setTextColor(color)
            container.findViewById<ImageView>(R.id.itemIcon).setColorFilter(color)
        }
    }

    private fun updateUI() {
        val currentUser = auth.currentUser

        val isDark = ThemeManager.isDarkMode(this)
        val glassRes = if (isDark) R.drawable.bg_glass_item else R.drawable.bg_glass_item_light
        btnProfileCard.setBackgroundResource(glassRes)
        btnProfileCard.backgroundTintList = null

        if (currentUser != null) {
            // Logged In
            val email = currentUser.email ?: "User"
            tvUserTitle.text = email
            tvUserSub.text = "Tap to Logout"

            vAvatarBg.setBackgroundResource(R.drawable.bg_premium_active)
            vAvatarBg.backgroundTintList = null
            ivAvatarIcon.setColorFilter(android.graphics.Color.WHITE)



            checkPremiumStatus(currentUser.uid)

            btnProfileCard.setOnClickListener { showLogoutDialog() }

        } else {
            // Guest
            tvUserTitle.text = "Guest User"
            tvUserSub.text = "Tap to Log In"

            tvBadge.text = "FREE"
            tvBadge.background.setTint(android.graphics.Color.parseColor("#33FFFFFF"))
            tvBadge.setTextColor(android.graphics.Color.WHITE)

            vAvatarBg.setBackgroundResource(R.drawable.bg_glass_button)
            vAvatarBg.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FFD54F"))
            ivAvatarIcon.setColorFilter(android.graphics.Color.WHITE)

            // Сбрасываем локальные переменные
            isUserPremium = false
            updatePremiumState(false)

            btnProfileCard.setOnClickListener {
                startActivity(Intent(this, AuthActivity::class.java))
            }
        }
    }

    private fun checkPremiumStatus(uid: String) {
        premiumListener?.remove()

        premiumListener = db.collection("users").document(uid)
            .addSnapshotListener { document, error ->
                if (error != null) return@addSnapshotListener

                if (document != null && document.exists()) {
                    // 1. Сохраняем данные в переменные класса
                    isUserPremium = document.getBoolean("isPremium") ?: false
                    userExpiryDate = document.getLong("subscriptionExpiry") ?: 0

                    // 2. Обновляем UI
                    updatePremiumState(isUserPremium)
                }
            }
    }

    // === НОВАЯ ФУНКЦИЯ: ПОКАЗАТЬ ИНФОРМАЦИЮ О ПОДПИСКЕ ===
    private fun showSubscriptionInfo() {
        val dateString = if (userExpiryDate > 0) {
            // Форматируем дату (например: 15 Oct 2025)
            val sdf = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
            sdf.format(Date(userExpiryDate))
        } else {
            "Lifetime"
        }

        GlassDialog.show(
            context = this,
            title = "Premium Active",
            message = "Your subscription is valid until:\n$dateString",
            iconRes = R.drawable.ic_crown, // Ваша иконка галочки/короны
            positiveText = "Awesome!"
        )
    }

    private fun updatePremiumState(isPremium: Boolean) {
        if (isPremium) {
            tvBadge.text = "PRO"
            tvBadge.background.setTint(android.graphics.Color.parseColor("#66BB6A"))
            tvBadge.setTextColor(android.graphics.Color.BLACK)

            btnPremium.setBackgroundResource(R.drawable.bg_premium_active)
            ivPremIcon.setImageResource(R.drawable.ic_crown)
            ivPremIcon.visibility = View.VISIBLE
            ivPremIcon.setColorFilter(android.graphics.Color.WHITE)

            tvPremTitle.text = "Premium Active"
            tvPremSub.text = "Subscription is valid"
            ivArrowPrem.visibility = View.VISIBLE
        } else {
            tvBadge.text = "FREE"
            tvBadge.background.setTint(android.graphics.Color.parseColor("#33FFFFFF"))
            tvBadge.setTextColor(android.graphics.Color.WHITE)

            btnPremium.setBackgroundResource(R.drawable.bg_premium_modern)
            ivPremIcon.setImageResource(R.drawable.ic_rocket)
            ivPremIcon.setColorFilter(android.graphics.Color.WHITE)

            tvPremTitle.text = "Upgrade to PRO"
            tvPremSub.text = "Remove ads & unlock speed"
            ivArrowPrem.visibility = View.VISIBLE
        }
    }

    private fun showLogoutDialog() {
        GlassDialog.show(
            context = this,
            title = "Log Out",
            message = "Are you sure you want to log out from your account?",
            iconRes = R.drawable.ic_info, // Иконка вопроса или инфо
            positiveText = "Log Out",
            negativeText = "Cancel",
            onPositiveClick = {
                auth.signOut()
                updateUI() // Или updateUI, как у вас названо
                ToastHelper.show(this, "Logged out", ToastHelper.Type.INFO)
            }
        )
    }

    private fun setupMenuItem(viewId: Int, title: String, iconRes: Int, onClick: () -> Unit) {
        val view = findViewById<View>(viewId)
        val tvTitle = view.findViewById<TextView>(R.id.itemTitle)
        val ivIcon = view.findViewById<ImageView>(R.id.itemIcon)
        tvTitle.text = title
        ivIcon.setImageResource(iconRes)
        view.setOnClickListener { onClick() }
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (e: Exception) {}
    }
}