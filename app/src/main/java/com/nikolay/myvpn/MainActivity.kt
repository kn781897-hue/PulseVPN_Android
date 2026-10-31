package com.nikolay.myvpn

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.TrafficStats
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    // Основные параметры подключения к новому серверу
    private var serverIp = "31.59.136.18"
    private var serverPort = 2082
    private var currentState = VpnState.DISCONNECTED
    private enum class VpnState { DISCONNECTED, CONNECTING, CONNECTED }
    private var isPremiumUser = false
    private var isCheckingSubscription = false
    private var connectionTimer: CountDownTimer? = null

    // Приемник изменения состояния VPN от сервиса
    private val vpnReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread {
                syncVpnState()
            }
        }
    }

    // UI вкладки
    private lateinit var tabDashboard: View
    private lateinit var tabSettings: View
    private lateinit var tabProfile: View
    private var currentTab = 0 // 0=Dash, 1=Settings, 2=Profile

    private lateinit var tvHeaderTitle: TextView
    private lateinit var navDash: ImageButton
    private lateinit var navSettings: ImageButton
    private lateinit var navProfile: ImageButton

    // Dashboard UI
    private lateinit var btnMain: android.widget.FrameLayout
    private lateinit var ivPower: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvIpProto: TextView

    // Settings UI
    private lateinit var tvDownload: TextView
    private lateinit var tvUpload: TextView
    private lateinit var tvPing: TextView
    private lateinit var topGraph: TrafficGraphView
    private val handler = Handler(Looper.getMainLooper())
    private var isUpdatingStats = false
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L

    // Цвета
    private val colorAccent = Color.parseColor("#5E5CE6")
    private val colorGreen = Color.parseColor("#0A84FF")
    private val colorCyan = Color.parseColor("#00E5FF")
    private val colorInactive = Color.parseColor("#8E8EA0")
    private val tabActiveBg = R.drawable.bg_glass_active_pill
    private val tabInactiveBg = android.R.color.transparent

    private val vpnPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startVpnService() else disconnect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        setContentView(R.layout.activity_main)

        tvHeaderTitle = findViewById(R.id.tvHeaderTitle)
        navDash = findViewById(R.id.navDash)
        navSettings = findViewById(R.id.navSettings)
        navProfile = findViewById(R.id.navProfile)

        tabDashboard = findViewById(R.id.tabDashboard)
        tabSettings = findViewById(R.id.tabSettings)
        tabProfile = findViewById(R.id.tabProfile)

        initDashboard()
        initSettings()
        initProfile()

        navDash.setOnClickListener { switchTab(0) }
        navSettings.setOnClickListener { switchTab(1) }
        navProfile.setOnClickListener { switchTab(2) }

        // Инициализируем 1-часовой триал для новых пользователей
        SessionManager.startTrialIfNeeded(this)

        // Инициализируем кэшированное состояние премиума мгновенно
        isPremiumUser = SubscriptionManager.isCachedPremium(this)

        // Регистрируем ресивер для отслеживания состояния VPN
        val filter = android.content.IntentFilter(MyVpnService.ACTION_STATE_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(vpnReceiver, filter)
        }

        // Синхронизируем текущее состояние подключения
        syncVpnState()

        // Показываем уведомление о триале, если еще не показывалось
        if (!SessionManager.hasShownNotice(this)) {
            showTrialWelcomeDialog()
            SessionManager.markNoticeShown(this)
        }

        fetchServerConfig()
        checkPremium()
        AppUpdateManager.checkUpdate(this, silent = true)
    }

    // Анимация переключения вкладок
    private fun switchTab(target: Int) {
        if (currentTab == target) return
        val isGoingRight = target > currentTab

        val currentView = when (currentTab) {
            0 -> tabDashboard; 1 -> tabSettings; else -> tabProfile
        }
        val targetView = when (target) {
            0 -> tabDashboard; 1 -> tabSettings; else -> tabProfile
        }

        currentView.animate()
            .translationX((if (isGoingRight) -currentView.width else currentView.width).toFloat())
            .alpha(0f)
            .setDuration(250)
            .withEndAction {
                currentView.visibility = View.GONE
                currentView.translationX = 0f
            }.start()

        targetView.visibility = View.VISIBLE
        targetView.translationX = (if (isGoingRight) targetView.width else -targetView.width).toFloat()
        targetView.alpha = 0f

        targetView.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(250)
            .start()

        navDash.setBackgroundResource(if (target == 0) tabActiveBg else tabInactiveBg)
        navDash.setColorFilter(if (target == 0) colorAccent else colorInactive)
        navSettings.setBackgroundResource(if (target == 1) tabActiveBg else tabInactiveBg)
        navSettings.setColorFilter(if (target == 1) colorAccent else colorInactive)
        navProfile.setBackgroundResource(if (target == 2) tabActiveBg else tabInactiveBg)
        navProfile.setColorFilter(if (target == 2) colorAccent else colorInactive)

        tvHeaderTitle.text = when (target) {
            0 -> "PULSE VPN"
            1 -> "НАСТРОЙКИ"
            else -> "ПРОФИЛЬ"
        }

        currentTab = target
    }

    // Дашборд
    private fun initDashboard() {
        btnMain = findViewById(R.id.btnMain)
        ivPower = findViewById(R.id.ivPower)
        tvStatus = findViewById(R.id.tvStatus)
        tvTimer = findViewById(R.id.tvTimer)
        tvIpProto = findViewById(R.id.tvIpProto)

        updateServerDisplay()

        btnMain.setOnClickListener {
            if (currentState == VpnState.CONNECTING) return@setOnClickListener
            btnMain.animate().scaleX(0.95f).scaleY(0.95f).setDuration(150).withEndAction {
                btnMain.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                handleToggle()
            }.start()
        }
    }

    private fun updateServerDisplay() {
        if (::tvIpProto.isInitialized) {
            val config = ServerConfigManager.getConfig(this)
            val protoLabel = if (config.security.equals("tls", ignoreCase = true) || config.network.equals("ws", ignoreCase = true)) {
                "VLESS CDN"
            } else if (config.protocol.equals("vless", ignoreCase = true)) {
                "VLESS Reality"
            } else {
                config.protocol.uppercase()
            }
            tvIpProto.text = "${config.ip} · $protoLabel"
        }
    }

    // Настройки
    private fun initSettings() {
        tvDownload = findViewById(R.id.tvDownload)
        tvUpload = findViewById(R.id.tvUpload)
        tvPing = findViewById(R.id.tvPing)
        topGraph = findViewById(R.id.vGraphFallback)

        val switchKill = findViewById<Switch>(R.id.switchKill)
        val switchNotif = findViewById<Switch>(R.id.switchNotif)
        val prefs = getSharedPreferences("VPN_SETTINGS", Context.MODE_PRIVATE)

        switchKill.isChecked = prefs.getBoolean("kill_switch", false)
        switchNotif.isChecked = prefs.getBoolean("notifications", true)

        switchKill.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("kill_switch", isChecked).apply()
        }
        switchNotif.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notifications", isChecked).apply()
        }

        val tvAppVersion = tabSettings.findViewById<TextView>(R.id.tvAppVersion)
        tvAppVersion?.text = "Версия ${BuildConfig.VERSION_NAME} · Нажмите для проверки"

        val btnCheckUpdates = tabSettings.findViewById<View>(R.id.btnCheckUpdates)
        btnCheckUpdates?.setOnClickListener {
            AppUpdateManager.checkUpdate(this, silent = false)
        }
    }

    // Профиль
    private fun initProfile() {
        val auth = FirebaseAuth.getInstance()
        val tvUserTitle = tabProfile.findViewById<TextView>(R.id.tvUserTitle)
        val tvUserSub = tabProfile.findViewById<TextView>(R.id.tvUserSub)
        val btnLogin = tabProfile.findViewById<View>(R.id.btnLogin)
        val btnPremium = tabProfile.findViewById<View>(R.id.btnPremium)
        val btnLinkTelegram = tabProfile.findViewById<View>(R.id.btnLinkTelegram)
        val tvTelegramLinkTitle = tabProfile.findViewById<TextView>(R.id.tvTelegramLinkTitle)
        val tvTelegramLinkSub = tabProfile.findViewById<TextView>(R.id.tvTelegramLinkSub)

        val user = auth.currentUser
        val tgKey = SubscriptionManager.getSavedTelegramKey(this)
        val tgUsername = SubscriptionManager.getTelegramUsername(this)
        val isGuest = SubscriptionManager.isLoggedOut(this) || (user == null && tgKey.isNullOrBlank() && tgUsername.isNullOrBlank())

        if (user != null && !isGuest) {
            tvUserTitle.text = user.email ?: "Пользователь"
            tvUserSub.text = if (isPremiumUser) "Подписка активна (Нажмите для выхода)" else "Авторизован (Нажмите для выхода)"
            btnLogin.setOnClickListener {
                showLogoutDialog {
                    auth.signOut()
                    SubscriptionManager.unlinkDevice(this) {
                        runOnUiThread {
                            isPremiumUser = false
                            initProfile()
                            updateStatusUi()
                            ToastHelper.show(this@MainActivity, "Вы успешно вышли из аккаунта", ToastHelper.Type.INFO)
                        }
                    }
                }
            }
        } else if (!isGuest && (!tgKey.isNullOrBlank() || !tgUsername.isNullOrBlank())) {
            tvUserTitle.text = if (!tgUsername.isNullOrBlank()) "@$tgUsername" else "Telegram: ${tgKey?.take(10)}..."
            tvUserSub.text = if (isPremiumUser) "Подписка активна (до 3 устр.) · Выйти" else "Ключ не активен · Выйти"
            btnLogin.setOnClickListener {
                showLogoutDialog {
                    SubscriptionManager.unlinkDevice(this) {
                        runOnUiThread {
                            isPremiumUser = false
                            initProfile()
                            updateStatusUi()
                            ToastHelper.show(this@MainActivity, "Вы успешно вышли из аккаунта", ToastHelper.Type.INFO)
                        }
                    }
                }
            }
        } else {
            tvUserTitle.text = "Гостевой доступ"
            val remainingTrial = SessionManager.getRemainingTime(this)
            val minutesLeft = remainingTrial / 60000
            tvUserSub.text = if (remainingTrial > 0) {
                "Пробный период: $minutesLeft мин осталось · Войти"
            } else {
                "Пробный период истек · Нажмите для входа"
            }
            btnLogin.setOnClickListener {
                startActivity(Intent(this, AuthActivity::class.java))
            }
        }

        // Telegram 1-Click Link
        if (!isGuest && (!tgUsername.isNullOrBlank() || !tgKey.isNullOrBlank())) {
            tvTelegramLinkTitle.text = if (!tgUsername.isNullOrBlank()) "Telegram: @$tgUsername" else "Telegram привязан"
            tvTelegramLinkSub.text = if (isPremiumUser) "Подписка синхронизирована (активна)" else "Синхронизировано · Нажмите для обновления"
        } else {
            tvTelegramLinkTitle.text = "Войти через Telegram"
            tvTelegramLinkSub.text = "1 клик — автоматическая привязка"
        }
        btnLinkTelegram.setOnClickListener {
            SubscriptionManager.openTelegramLink(this)
        }

        btnPremium.setOnClickListener {
            startActivity(Intent(this, PremiumActivity::class.java))
        }

        setupMenuItem(R.id.btnTelegram, "Telegram Канал и Бот", R.drawable.ic_telegram) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/PullseVpn_bot")))
        }

        setupMenuItem(R.id.btnShare, "Поделиться", R.drawable.ic_share) {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, "Попробуй PulseVPN: единый ключ на 3 устройства через Telegram @PullseVpn_bot")
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, "Поделиться через"))
        }

        setupMenuItem(R.id.btnRate, "Оценить нас", R.drawable.ic_star) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://pulsevpn.shop")))
        }

        setupMenuItem(R.id.btnAbout, "О приложении", R.drawable.ic_info) {
            android.widget.Toast.makeText(this, "PulseVPN v${BuildConfig.VERSION_NAME} · Сервер $serverIp", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLogoutDialog(onConfirm: () -> Unit) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Выход из аккаунта")
            .setMessage("Вы уверены, что хотите выйти? Текущее устройство будет отвязано.")
            .setPositiveButton("Выйти") { _, _ -> onConfirm() }
            .setNegativeButton("Отмена", null)
            .create()
        dialog.show()
    }

    private fun setupMenuItem(itemId: Int, title: String, iconResId: Int, onClick: () -> Unit) {
        val item = tabProfile.findViewById<View>(itemId) ?: findViewById<View>(itemId) ?: return
        item.findViewById<TextView>(R.id.itemTitle)?.text = title
        item.findViewById<ImageView>(R.id.itemIcon)?.setImageResource(iconResId)
        item.setOnClickListener { onClick() }
    }

    override fun onResume() {
        super.onResume()
        AppUpdateManager.checkPendingInstall(this)
        syncVpnState()
        checkPremium()
        isUpdatingStats = true
        lastRxBytes = TrafficStats.getTotalRxBytes()
        lastTxBytes = TrafficStats.getTotalTxBytes()
        handler.post(statsRunnable)
        updatePing()
    }

    override fun onPause() {
        super.onPause()
        isUpdatingStats = false
        handler.removeCallbacks(statsRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        ServerConfigManager.stopListening()
        try {
            unregisterReceiver(vpnReceiver)
        } catch (e: Exception) {}
        connectionTimer?.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    private fun syncVpnState() {
        val isActive = MyVpnService.isVpnActive(this)
        if (isActive) {
            if (currentState != VpnState.CONNECTED) {
                currentState = VpnState.CONNECTED
                tvStatus.text = "ПОДКЛЮЧЕНО"
                tvStatus.setTextColor(colorCyan)
                ivPower.setColorFilter(colorCyan)
                ivPower.setImageResource(android.R.drawable.ic_lock_power_off)
                startUiTimer()
            }
        } else {
            if (currentState != VpnState.DISCONNECTED) {
                currentState = VpnState.DISCONNECTED
                tvStatus.setTextColor(colorInactive)
                ivPower.setColorFilter(Color.WHITE)
                ivPower.setImageResource(android.R.drawable.ic_lock_power_off)
                connectionTimer?.cancel()
                tvTimer.text = "00:00:00"
            }
            updateStatusUi()
        }
    }

    private fun fetchServerConfig() {
        ServerConfigManager.startListening(this) { updatedConfig ->
            runOnUiThread {
                serverIp = updatedConfig.ip
                serverPort = updatedConfig.port
                updateServerDisplay()
            }
        }
    }

    private fun checkPremium() {
        isCheckingSubscription = true
        SubscriptionManager.checkSubscriptionStatus(this) { isSub ->
            isPremiumUser = isSub
            isCheckingSubscription = false
            runOnUiThread {
                initProfile()
                updateStatusUi()
            }
        }
    }

    private fun updateStatusUi() {
        if (currentState == VpnState.DISCONNECTED) {
            if (isPremiumUser) {
                tvStatus.text = "ГОТОВ К ПОДКЛЮЧЕНИЮ"
            } else if (SessionManager.isTrialActive(this)) {
                val mins = SessionManager.getRemainingTime(this) / 60000
                tvStatus.text = "ПРОБНЫЙ ПЕРИОД: $mins МИН"
            } else {
                tvStatus.text = "ПРОБНЫЙ ПЕРИОД ИСТЕК"
            }
        }
    }

    // Обработка кнопки подключения
    private fun handleToggle() {
        // Если VPN уже подключен или сервис активен — выключаем сразу с первого клика!
        if (currentState == VpnState.CONNECTED || MyVpnService.isVpnActive(this)) {
            disconnect()
            return
        }

        if (currentState == VpnState.CONNECTING) return

        val isTrialActive = SessionManager.isTrialActive(this)
        val isCached = SubscriptionManager.isCachedPremium(this)

        if (isPremiumUser || isCached || isTrialActive) {
            connect()
        } else if (isCheckingSubscription) {
            // Если в данный момент идет первая загрузка статуса подписки из сети
            currentState = VpnState.CONNECTING
            tvStatus.text = "ПРОВЕРКА ДОСТУПА..."
            tvStatus.setTextColor(colorAccent)
            ivPower.setColorFilter(colorAccent)

            handler.postDelayed({
                if (isPremiumUser || SubscriptionManager.isCachedPremium(this) || SessionManager.isTrialActive(this)) {
                    connect()
                } else {
                    currentState = VpnState.DISCONNECTED
                    updateStatusUi()
                    showTrialExpiredDialog()
                }
            }, 1000)
        } else {
            showTrialExpiredDialog()
        }
    }

    private fun connect() {
        currentState = VpnState.CONNECTING
        tvStatus.text = "ПОДКЛЮЧЕНИЕ..."
        tvStatus.setTextColor(colorAccent)
        ivPower.setColorFilter(colorAccent)
        ivPower.setImageResource(android.R.drawable.ic_lock_power_off)

        val intent = android.net.VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent) else startVpnService()

        handler.postDelayed({ setConnectedState() }, 2000)
    }

    private fun disconnect() {
        currentState = VpnState.DISCONNECTED
        tvStatus.setTextColor(colorInactive)
        ivPower.setColorFilter(Color.WHITE)
        ivPower.setImageResource(android.R.drawable.ic_lock_power_off)
        ivPower.animate().cancel()
        ivPower.rotation = 0f

        stopVpnService()
        connectionTimer?.cancel()
        tvTimer.text = "00:00:00"
        updateStatusUi()
    }

    private fun setConnectedState() {
        currentState = VpnState.CONNECTED
        tvStatus.text = "ПОДКЛЮЧЕНО"
        tvStatus.setTextColor(colorCyan)
        ivPower.setColorFilter(colorCyan)
        ivPower.setImageResource(android.R.drawable.ic_lock_power_off)
        ivPower.animate().cancel()
        ivPower.rotation = 0f

        btnMain.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200).withEndAction {
            btnMain.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
        }.start()

        startUiTimer()
    }

    private fun startUiTimer() {
        connectionTimer?.cancel()
        val isTrial = !isPremiumUser && SessionManager.isTrialActive(this)
        val startTime = MyVpnService.getStartTime(this)
        val initialElapsed = if (!isTrial) {
            val diff = System.currentTimeMillis() - startTime
            if (diff > 0) diff else 0L
        } else 0L

        connectionTimer = object : CountDownTimer(86400000L, 1000) {
            var elapsed = initialElapsed

            override fun onTick(ms: Long) {
                if (isTrial) {
                    val remainingMs = SessionManager.getRemainingTime(this@MainActivity)
                    if (remainingMs <= 0) {
                        disconnect()
                        showTrialExpiredDialog()
                        return
                    }
                    val h = remainingMs / 1000 / 3600
                    val m = (remainingMs / 1000 / 60) % 60
                    val s = (remainingMs / 1000) % 60
                    tvTimer.text = String.format("%02d:%02d:%02d", h, m, s)
                } else {
                    elapsed += 1000
                    val h = elapsed / 1000 / 3600
                    val m = (elapsed / 1000 / 60) % 60
                    val s = (elapsed / 1000) % 60
                    tvTimer.text = String.format("%02d:%02d:%02d", h, m, s)
                }
            }

            override fun onFinish() {
                disconnect()
            }
        }.start()
    }

    private fun startVpnService() {
        val customUuid = SubscriptionManager.getCustomUuid(this) ?: "d8352dcf-0a64-41cf-bb80-94ea6a1d30d7"
        val config = ServerConfigManager.getConfig(this)
        val intent = Intent(this, MyVpnService::class.java).apply {
            action = "START"
            putExtra("SERVER_IP", config.ip)
            putExtra("SERVER_PORT", config.port)
            putExtra("SERVER_UUID", customUuid)
            putExtra("SERVER_PROTOCOL", config.protocol)
            putExtra("SERVER_SECURITY", config.security)
            putExtra("SERVER_NETWORK", config.network)
            putExtra("SERVER_PATH", config.path)
            putExtra("SERVER_SNI", config.sni)
            putExtra("SERVER_PUBLIC_KEY", config.publicKey)
            putExtra("SERVER_SHORT_ID", config.shortId)
            putExtra("SERVER_FINGERPRINT", config.fingerprint)
            putExtra("SERVER_FLOW", config.flow)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVpnService() {
        startService(Intent(this, MyVpnService::class.java).apply { action = "STOP" })
    }

    // Уведомление о старте 1-часового триала
    private fun showTrialWelcomeDialog() {
        GlassDialog.show(
            context = this,
            title = "Пробный доступ 1 час",
            message = "Вам предоставлен 1 час бесплатного VPN без ограничений скорости.\n\nПротестируйте стабильность соединения и в любой момент привяжите Telegram для доступа со всех устройств.",
            iconRes = R.drawable.ic_hourglass,
            positiveText = "Начать тест",
            negativeText = "Вход через Telegram",
            onNegativeClick = {
                startActivity(Intent(this, AuthActivity::class.java))
            }
        )
    }

    // Уведомление об окончании триала
    private fun showTrialExpiredDialog() {
        GlassDialog.show(
            context = this,
            title = "Пробный период завершен",
            message = "Бесплатный 1-часовой период окончен.\n\nДля продолжения авторизуйтесь через Telegram-бота @PullseVpn_bot (единый ключ до 3 устройств) или оформите PRO подписку.",
            iconRes = R.drawable.ic_hourglass,
            positiveText = "Войти через Telegram",
            negativeText = "PRO тарифы",
            onNegativeClick = {
                startActivity(Intent(this, PremiumActivity::class.java))
            },
            onPositiveClick = {
                startActivity(Intent(this, AuthActivity::class.java))
            }
        )
    }

    private val statsRunnable = object : Runnable {
        override fun run() {
            if (!isUpdatingStats) return

            val currentRxBytes = TrafficStats.getTotalRxBytes()
            val currentTxBytes = TrafficStats.getTotalTxBytes()
            val rxDelta = currentRxBytes - lastRxBytes
            val txDelta = currentTxBytes - lastTxBytes

            lastRxBytes = currentRxBytes
            lastTxBytes = currentTxBytes

            val rxMbits = (rxDelta * 8.0) / 1_000_000.0
            val txMbits = (txDelta * 8.0) / 1_000_000.0

            tvDownload.text = String.format("%.1f", rxMbits)
            tvUpload.text = String.format("%.1f", txMbits)

            if (currentState == VpnState.CONNECTED) {
                topGraph.addDataPoint((rxMbits + txMbits).toFloat())
            } else {
                topGraph.addDataPoint(0f)
            }

            handler.postDelayed(this, 1000)
        }
    }

    private fun updatePing() {
        thread {
            try {
                val process = Runtime.getRuntime().exec("/system/bin/ping -c 1 -W 1 8.8.8.8")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var pingResult = "0"
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line?.contains("time=") == true) {
                        val start = line!!.indexOf("time=") + 5
                        val end = line!!.indexOf(" ms")
                        if (start != -1 && end != -1) {
                            pingResult = line!!.substring(start, end).toFloat().toInt().toString()
                        }
                    }
                }
                process.waitFor()
                handler.post { tvPing.text = pingResult }
            } catch (e: Exception) {
                handler.post { tvPing.text = "0" }
            }
        }.also {
            handler.postDelayed({ if (isUpdatingStats) updatePing() }, 5000)
        }
    }
}