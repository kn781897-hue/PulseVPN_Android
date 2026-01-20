package com.nikolay.myvpn

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView

object ThemeManager {

    // --- НАСТРОЙКИ ЦВЕТОВ ---

    // ТЕМНАЯ ТЕМА (Dark Forest)
    private val TEXT_DARK = Color.parseColor("#FFFFFF")
    private val SUBTEXT_DARK = Color.parseColor("#80FFFFFF")
    private val ICON_DARK = Color.parseColor("#FFFFFF")
    private val GLASS_DARK = Color.parseColor("#260F1520") // Темное стекло
    private val OVERLAY_DARK = Color.parseColor("#4D12141D") // Темная вуаль

    // СВЕТЛАЯ ТЕМА (Day Frost)
    private val BG_LIGHT = Color.parseColor("#F2F5F8") // Светло-серый фон
    private val TEXT_LIGHT = Color.parseColor("#12141D") // Черный текст
    private val SUBTEXT_LIGHT = Color.parseColor("#8012141D")
    private val ICON_LIGHT = Color.parseColor("#12141D")
    private val GLASS_LIGHT = Color.parseColor("#E6FFFFFF")
    private val OVERLAY_LIGHT = Color.parseColor("#4DFFFFFF")

    // --- ЛОГИКА ---

    fun isDarkMode(context: Context): Boolean {
        return context.getSharedPreferences("VPN_SETTINGS", Context.MODE_PRIVATE)
            .getBoolean("dark_mode", true)
    }

    fun applyTheme(activity: Activity) {
        val isDark = isDarkMode(activity)
        val rootLayout = activity.findViewById<View>(android.R.id.content)
        val overlay = activity.findViewById<View>(R.id.viewOverlay)

        // В ОБОИХ случаях ставим картинку леса
        rootLayout.setBackgroundResource(R.drawable.bg_nature)

        if (isDark) {
            // Темная вуаль
            overlay?.setBackgroundColor(OVERLAY_DARK)
        } else {
            // Светлая вуаль (Туман)
            overlay?.setBackgroundColor(OVERLAY_LIGHT)
        }
    }

    // 1. ТЕКСТ (Заголовки)
    fun applyTextColors(activity: Activity, vararg views: TextView?) {
        val color = if (isDarkMode(activity)) TEXT_DARK else TEXT_LIGHT
        views.forEach { it?.setTextColor(color) }
    }

    // 2. ПОДТЕКСТ (Описания)
    fun applySubTextColors(activity: Activity, vararg views: TextView?) {
        val color = if (isDarkMode(activity)) SUBTEXT_DARK else SUBTEXT_LIGHT
        views.forEach { it?.setTextColor(color) }
    }

    // 3. ИКОНКИ (Картинки ImageView)
    fun applyIconColors(activity: Activity, vararg views: ImageView?) {
        val color = if (isDarkMode(activity)) ICON_DARK else ICON_LIGHT
        views.forEach { it?.setColorFilter(color) }
    }

    // 4. КНОПКИ (ImageButton) - ВОТ ЭТОЙ ФУНКЦИИ НЕ ХВАТАЛО
    fun applyBtnColors(activity: Activity, vararg views: ImageButton?) {
        val color = if (isDarkMode(activity)) ICON_DARK else ICON_LIGHT
        views.forEach { it?.setColorFilter(color) }
    }

    // 5. СТЕКЛО (Фоны кнопок)
    // 5. СТЕКЛО (Фоны кнопок) - ИСПРАВЛЕННАЯ ВЕРСИЯ
    fun applyGlassColors(activity: Activity, vararg views: View?) {
        val isDark = isDarkMode(activity)

        // Выбираем файл фона в зависимости от темы
        val backgroundRes = if (isDark) {
            R.drawable.bg_glass_item // Ваш темный фон с белой рамкой
        } else {
            R.drawable.bg_glass_item_light // Новый светлый фон с темной рамкой
        }

        views.forEach { view ->
            // 1. Сбрасываем старую заливку, которая убивала обводку
            view?.backgroundTintList = null

            // 2. Устанавливаем правильный файл фона
            view?.setBackgroundResource(backgroundRes)
        }
    }
}