package com.nikolay.myvpn

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Инициализация кнопки Назад
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener { finish() }

        // Переключатели
        val switchKill = findViewById<Switch>(R.id.switchKill)
        val switchNotif = findViewById<Switch>(R.id.switchNotif)
        val switchDarkMode = findViewById<Switch>(R.id.switchDarkMode)

        // Загрузка настроек
        val prefs = getSharedPreferences("VPN_SETTINGS", Context.MODE_PRIVATE)

        switchKill.isChecked = prefs.getBoolean("kill_switch", false)
        switchNotif.isChecked = prefs.getBoolean("notifications", true)

        // Проверяем текущую тему
        val isDark = prefs.getBoolean("dark_mode", true)
        switchDarkMode.isChecked = isDark

        // 1. ПРИМЕНЯЕМ ТЕМУ ПРИ ЗАПУСКЕ ЭКРАНА
        updateScreenTheme()

        // 2. ЛОГИКА KILL SWITCH
        switchKill.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("kill_switch", isChecked).apply()
            if (isChecked) {
                showKillSwitchDialog()
            }
        }

        // 3. ЛОГИКА УВЕДОМЛЕНИЙ
        switchNotif.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notifications", isChecked).apply()
        }

        // 4. ЛОГИКА СМЕНЫ ТЕМЫ
        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            // Сохраняем выбор
            prefs.edit().putBoolean("dark_mode", isChecked).apply()

            // Мгновенно перекрашиваем экран без перезагрузки
            updateScreenTheme()

            val modeName = if (isChecked) "Dark Mode" else "Light Mode"
            ToastHelper.show(this, modeName, ToastHelper.Type.SUCCESS)
        }
        findViewById<android.view.View>(R.id.containerProto).setOnClickListener {
            ToastHelper.show(
                this,
                "Feature coming soon in next updates!",
                ToastHelper.Type.INFO
            )
        }
    }

    // Функция для обновления цветов всех элементов на этом экране
    // Функция для обновления цветов всех элементов на этом экране
    // Внутри SettingsActivity.kt

    private fun updateScreenTheme() {
        // 1. Фон
        ThemeManager.applyTheme(this)

        // 2. Тексты
        ThemeManager.applyTextColors(this,
            findViewById(R.id.tvSettingsTitle),
            findViewById(R.id.tvKillTitle),
            // Заголовки блоков
            findViewById(R.id.tvProtoTitle), // Добавьте ID в XML, если нет
            findViewById(R.id.tvNotifTitle), // Добавьте ID в XML, если нет
            findViewById(R.id.tvDarkTitle)   // Добавьте ID в XML, если нет
        )

        // 3. Подписи
        ThemeManager.applySubTextColors(this,
            findViewById(R.id.sub1),
            findViewById(R.id.tvProtoSub)
        )

        // 4. Иконки
        ThemeManager.applyIconColors(this,
            findViewById(R.id.btnBack),
            findViewById(R.id.ivSettingsIcon)
        )

        // 5. !!! СТЕКЛЯННЫЕ ПЛАШКИ !!!
        // Найдите ID контейнеров (ConstraintLayout) в activity_settings.xml
        // Если у них нет ID, добавьте их в XML:
        // id="@+id/containerKill", id="@+id/containerProto" и т.д.

        ThemeManager.applyGlassColors(this,
            findViewById(R.id.containerKill),
            findViewById(R.id.containerProto),
            findViewById(R.id.containerNotif),
            findViewById(R.id.containerDark)
        )
    }

    private fun showKillSwitchDialog() {
        GlassDialog.show(
            context = this,
            title = "Enable Kill Switch",
            message = "For 100% security, Android requires you to enable 'Block connections without VPN' in system settings.\n\nOpen Settings now?",
            iconRes = R.drawable.ic_info, // Можно использовать ic_info или добавить иконку щита
            positiveText = "Open Settings",
            negativeText = "Later",
            onPositiveClick = {
                try {
                    val intent = Intent(Settings.ACTION_VPN_SETTINGS)
                    startActivity(intent)
                } catch (e: Exception) {
                    ToastHelper.show(this, "Cannot open settings", ToastHelper.Type.ERROR)
                }
            }
        )
    }
}