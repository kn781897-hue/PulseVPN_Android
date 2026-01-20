package com.nikolay.myvpn

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import android.view.animation.AccelerateDecelerateInterpolator
import android.animation.Keyframe
import android.animation.PropertyValuesHolder
import android.os.CountDownTimer

import android.widget.ImageButton

class MainActivity : AppCompatActivity() {

    private var currentState = VpnState.DISCONNECTED
    private enum class VpnState { DISCONNECTED, CONNECTING, CONNECTED }

    private lateinit var btnMain: CardView
    private lateinit var ivIcon: ImageView
    private lateinit var ivLoadingRing: ImageView
    private lateinit var tvStatus: TextView

    private lateinit var btnMenu: ImageButton

    private lateinit var btnSettings: ImageButton

    // Переменная, хранящая статус
    private var isPremiumUser = false

    // Аниматоры
    private var rotateAnimator: ObjectAnimator? = null    // Для кометы
    private var hourglassAnimator: ObjectAnimator? = null // Для песочных часов

    // Цвета
    private val colorSun = Color.parseColor("#FFD54F")
    private val colorGlassDark = Color.parseColor("#990F1520")
    private val colorTextWhite = Color.parseColor("#CCFFFFFF")

    // Специальный лаунчер для обработки результата системного окна
    private val vpnPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Пользователь нажал "ОК" -> Запускаем!
            startVpnService()
        } else {
            // Пользователь нажал "Отмена" -> Сбрасываем UI
            disconnect()
        }
    }

    private var connectionTimer: CountDownTimer? = null

    // Время сессии (1 час в миллисекундах)
    private val SESSION_TIME_MS = 3600000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        SessionManager.expireSession(this)//Надо будет удалить

        SubscriptionManager.checkSubscriptionStatus { isPremium ->
        }

        btnMain = findViewById(R.id.btnMain)
        ivIcon = findViewById(R.id.ivIcon)
        ivLoadingRing = findViewById(R.id.ivLoadingRing)
        tvStatus = findViewById(R.id.tvStatus)
        btnMenu = findViewById(R.id.btnMenu)
        btnSettings = findViewById(R.id.btnSettings)

        AdManager.loadAd(this)

        btnMain.setOnClickListener {
            if (currentState == VpnState.CONNECTING) return@setOnClickListener
            handleToggle()
        }


        // Логика МЕНЮ
        btnMenu.setOnClickListener {
            // Анимация
            btnMenu.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction {
                btnMenu.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }.start()

            // ПЕРЕХОД НА ЭКРАН МЕНЮ
            val intent = Intent(this, MenuActivity::class.java)
            startActivity(intent)
            // Добавляем плавную анимацию перехода (опционально)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        // Логика НАСТРОЕК
        btnSettings.setOnClickListener {
            // Анимация
            btnSettings.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction {
                btnSettings.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }.start()

            // ПЕРЕХОД НА ЭКРАН НАСТРОЕК
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

    }

    override fun onResume() {
        super.onResume()

        // Применяем тему
        ThemeManager.applyTheme(this)

        // Красим элементы
        ThemeManager.applyTextColors(this, findViewById(R.id.tvAppName))

        // Кнопки меню и настроек
        ThemeManager.applyBtnColors(this,
            findViewById(R.id.btnMenu),
            findViewById(R.id.btnSettings)
        )

        SubscriptionManager.checkSubscriptionStatus { isActive ->
            isPremiumUser = isActive

            // Если мы уже подключены и купили премиум - обновляем текст сразу
            if (currentState == VpnState.CONNECTED && isPremiumUser) {
                connectionTimer?.cancel() // Убираем таймер
                tvStatus.text = "CONNECTED • UNLIMITED"
            }
        }

        // Статус (если он не цветной)
        // ThemeManager.applySubTextColors(this, findViewById(R.id.tvStatus))
    }

    private fun handleToggle() {
        if (currentState == VpnState.CONNECTED) {
            disconnect()
        } else {
            // === ИЗМЕНЕНИЕ: ПОКАЗАТЬ РЕКЛАМУ ПЕРЕД ПОДКЛЮЧЕНИЕМ ===
            // Сначала анимация нажатия...
            btnMain.animate().scaleX(0.9f).scaleY(0.9f).setDuration(150).withEndAction {
                btnMain.animate().scaleX(1f).scaleY(1f).setDuration(150).start()

                // Проверяем премиум (если есть - рекламу не показываем)
                // Но пока считаем, что показываем всем

                if (isPremiumUser) {
                    // ЕСЛИ ПРЕМИУМ: Сразу подключаем, без рекламы и проверок
                    connect()
                } else {
                    // ЕСЛИ БЕСПЛАТНО: Проверяем сессию и рекламу
                    if (SessionManager.isSessionActive(this)) {
                        connect()
                    } else {
                        tvStatus.text = "LOADING AD..."
                        AdManager.showAd(this) {
                            SessionManager.startNewSession(this)
                            connect()
                        }
                    }
                }
            }.start()
        }
    }

    private fun connect() {
        currentState = VpnState.CONNECTING
        tvStatus.text = "INITIALIZING..."
        tvStatus.setTextColor(colorSun)

        // Сжатие кнопки
        btnMain.animate().scaleX(0.95f).scaleY(0.95f).setDuration(150).withEndAction {
            btnMain.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        }.start()

        // Запуск кометы
        startCometAnimation()

        // === СМЕНА ИКОНКИ ===
        // Сначала плавно прячем старую иконку
        ivIcon.animate().alpha(0f).setDuration(200).withEndAction {
            // Меняем картинку
            ivIcon.setImageResource(R.drawable.ic_hourglass)
            ivIcon.setColorFilter(colorSun)

            // И запускаем новую анимацию (она сама сделает Fade In)
            startHourglassAnimation()
        }.start()

        // === ВАЖНОЕ ИЗМЕНЕНИЕ ===
        // Спрашиваем у системы: "Можно запустить VPN?"
        val intent = android.net.VpnService.prepare(this)

        if (intent != null) {
            // Разрешения нет -> Показываем системное окно
            vpnPermissionLauncher.launch(intent)
        } else {
            // Разрешение уже есть -> Запускаем сразу
            startVpnService()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (currentState == VpnState.CONNECTING) {
                setConnectedState()
            }
        }, 4000)
    }

    private fun disconnect() {
        currentState = VpnState.DISCONNECTED
        tvStatus.text = "TAP TO CONNECT"
        tvStatus.setTextColor(colorTextWhite)

        stopVpnService()
        stopAnimations()
        connectionTimer?.cancel()
        connectionTimer = null

        btnMain.setCardBackgroundColor(colorGlassDark)

        ivIcon.animate().alpha(0f).setDuration(200).withEndAction {
            ivIcon.setImageResource(android.R.drawable.ic_lock_power_off)
            ivIcon.setColorFilter(Color.WHITE)
            ivIcon.animate().alpha(1f).setDuration(200).start()
        }.start()

    }

    private fun setConnectedState() {
        currentState = VpnState.CONNECTED
        tvStatus.text = "SECURED"
        tvStatus.setTextColor(colorSun)

        // Убираем комету
        ivLoadingRing.animate().alpha(0f).setDuration(1000).withEndAction {
            rotateAnimator?.cancel()
            ivLoadingRing.visibility = View.INVISIBLE
        }.start()

        // Останавливаем часы
        hourglassAnimator?.cancel()

        // Кнопка остается темной
        btnMain.setCardBackgroundColor(colorGlassDark)

        // === ПЛАВНАЯ СМЕНА НА ЗАМОК ===
        // Исчезаем часы
        ivIcon.animate().alpha(0f).setDuration(300).withEndAction {
            // Сбрасываем поворот (чтобы замок не был кривым)
            ivIcon.rotation = 0f

            // Ставим замок
            ivIcon.setImageResource(android.R.drawable.ic_lock_power_off)
            ivIcon.setColorFilter(colorSun)

            // Появляем замок
            ivIcon.animate().alpha(1f).setDuration(300).start()
        }.start()

        if (isPremiumUser) {
            // ЕСЛИ ПРЕМИУМ: Пишем "Безлимит" и не запускаем таймер
            tvStatus.text = "CONNECTED • UNLIMITED"
        } else {
            // ЕСЛИ БЕСПЛАТНО: Запускаем таймер
            startUiTimer()
        }
    }

    // --- АНИМАЦИИ ---

    private fun startCometAnimation() {
        ivLoadingRing.visibility = View.VISIBLE
        ivLoadingRing.alpha = 0f
        ivLoadingRing.rotation = 90f

        ivLoadingRing.animate().alpha(1f).setDuration(1500).setInterpolator(LinearInterpolator()).start()

        rotateAnimator = ObjectAnimator.ofFloat(ivLoadingRing, "rotation", 90f, 450f).apply {
            duration = 3000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    // Вращение часов
    private fun startHourglassAnimation() {
        // Подготовка: Резкое появление (за 200мс)
        ivIcon.alpha = 0f
        ivIcon.rotation = 0f
        ivIcon.animate().alpha(1f).setDuration(200).start()

        // --- НАСТРОЙКА ТАЙМИНГА ---

        // 1. Старт (0 градусов)
        val k0 = Keyframe.ofFloat(0f, 0f)

        // 2. Первый переворот (заканчивается на 35% времени)
        val k1 = Keyframe.ofFloat(0.35f, 180f)
        k1.interpolator = AccelerateDecelerateInterpolator() // Разгон-Торможение

        // 3. ПАУЗА (Длинная "мертвая точка" до 65% времени)
        // С 35% до 65% значение не меняется (180 градусов)
        val k2 = Keyframe.ofFloat(0.65f, 180f)

        // 4. Второй переворот (до 360 градусов к концу)
        val k3 = Keyframe.ofFloat(1f, 360f)
        k3.interpolator = AccelerateDecelerateInterpolator()

        val pvhRotation = PropertyValuesHolder.ofKeyframe("rotation", k0, k1, k2, k3)

        hourglassAnimator = ObjectAnimator.ofPropertyValuesHolder(ivIcon, pvhRotation).apply {
            duration = 3000 // Общее время цикла 3 секунды (пауза будет почти 1 секунду)
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
            start()
        }
    }

    private fun stopAnimations() {
        rotateAnimator?.cancel()
        hourglassAnimator?.cancel() // Останавливаем часы

        ivIcon.rotation = 0f

        ivLoadingRing.animate().alpha(0f).setDuration(500).withEndAction {
            ivLoadingRing.visibility = View.INVISIBLE
        }.start()
    }

    private fun startVpnService() {
        val intent = Intent(this, MyVpnService::class.java)
        intent.action = "START"
        // Передаем флаг премиума
        intent.putExtra("IS_PREMIUM", isPremiumUser)
        startService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, MyVpnService::class.java)
        intent.action = "STOP"
        startService(intent)
    }

    private fun startUiTimer() {
        connectionTimer?.cancel()

        // Получаем, сколько реально осталось времени
        val timeHeader = SessionManager.getRemainingTime(this)

        if (timeHeader <= 0) {
            disconnect()
            return
        }

        // Запускаем таймер на остаток времени
        connectionTimer = object : android.os.CountDownTimer(timeHeader, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val minutes = millisUntilFinished / 1000 / 60
                val seconds = millisUntilFinished / 1000 % 60
                val timeString = String.format("%02d:%02d", minutes, seconds)

                tvStatus.text = "CONNECTED • $timeString"
            }

            override fun onFinish() {
                tvStatus.text = "SESSION EXPIRED"
                disconnect()
            }
        }.start()
    }
}