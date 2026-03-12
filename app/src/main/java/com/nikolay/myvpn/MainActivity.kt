package com.nikolay.myvpn

import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import androidx.cardview.widget.CardView
import androidx.core.view.WindowCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    // --- VPN Logic ---
    private var serverIp = "31.58.87.8"
    private var serverPort = 80
    private var currentState = VpnState.DISCONNECTED
    private enum class VpnState { DISCONNECTED, CONNECTING, CONNECTED }
    private var isPremiumUser = false
    private var connectionTimer: CountDownTimer? = null
    
    // --- UI Tabs ---
    private lateinit var tabDashboard: View
    private lateinit var tabSettings: View
    private lateinit var tabProfile: View
    private var currentTab = 0 // 0=Dash, 1=Settings, 2=Profile

    private lateinit var tvHeaderTitle: TextView
    private lateinit var navDash: ImageButton
    private lateinit var navSettings: ImageButton
    private lateinit var navProfile: ImageButton

    // --- Dashboard UI ---
    private lateinit var btnMain: android.widget.FrameLayout
    private lateinit var ivPower: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView

    // --- Settings UI ---
    private lateinit var tvDownload: TextView
    private lateinit var tvUpload: TextView
    private lateinit var tvPing: TextView
    private lateinit var topGraph: TrafficGraphView
    private val handler = Handler(Looper.getMainLooper())
    private var isUpdatingStats = false
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L

    // Colors
    private val colorAccent = Color.parseColor("#5E5CE6")
    private val colorGreen = Color.parseColor("#0A84FF") // Neon Blue now
    private val colorCyan = Color.parseColor("#00E5FF") // Cyan for active state
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
        
        // Remove completely any shadow/veil behind navigation bar on newer Android versions
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

        // Init UI chunks
        initDashboard()
        initSettings()
        initProfile()

        // Nav switchers
        navDash.setOnClickListener { switchTab(0) }
        navSettings.setOnClickListener { switchTab(1) }
        navProfile.setOnClickListener { switchTab(2) }

        fetchServerConfig()
        SubscriptionManager.checkSubscriptionStatus { }
    }

    // --- TAB SWITCH ANIMATION ---
    private fun switchTab(target: Int) {
        if (currentTab == target) return
        val isGoingRight = target > currentTab

        val currentView = when (currentTab) {
            0 -> tabDashboard; 1 -> tabSettings; else -> tabProfile
        }
        val targetView = when (target) {
            0 -> tabDashboard; 1 -> tabSettings; else -> tabProfile
        }

        // Animate Out
        currentView.animate()
            .translationX((if (isGoingRight) -currentView.width else currentView.width).toFloat())
            .alpha(0f)
            .setDuration(250)
            .withEndAction {
                currentView.visibility = View.GONE
                currentView.translationX = 0f
            }.start()

        // Setup Target
        targetView.visibility = View.VISIBLE
        targetView.translationX = (if (isGoingRight) targetView.width else -targetView.width).toFloat()
        targetView.alpha = 0f

        // Animate In
        targetView.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(250)
            .start()

        // Update nav buttons
        navDash.setBackgroundResource(if (target == 0) tabActiveBg else tabInactiveBg)
        navDash.setColorFilter(if (target == 0) colorAccent else colorInactive)
        navSettings.setBackgroundResource(if (target == 1) tabActiveBg else tabInactiveBg)
        navSettings.setColorFilter(if (target == 1) colorAccent else colorInactive)
        navProfile.setBackgroundResource(if (target == 2) tabActiveBg else tabInactiveBg)
        navProfile.setColorFilter(if (target == 2) colorAccent else colorInactive)

        tvHeaderTitle.text = when (target) {
            0 -> "PULSE VPN"
            1 -> "SETTINGS"
            else -> "PROFILE"
        }

        currentTab = target
    }

    // --- INIT Dashboard ---
    private fun initDashboard() {
        btnMain = findViewById(R.id.btnMain)
        ivPower = findViewById(R.id.ivPower)
        tvStatus = findViewById(R.id.tvStatus)
        tvTimer = findViewById(R.id.tvTimer)

        btnMain.setOnClickListener {
            if (currentState == VpnState.CONNECTING) return@setOnClickListener
            btnMain.animate().scaleX(0.95f).scaleY(0.95f).setDuration(150).withEndAction {
                btnMain.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                handleToggle()
            }.start()
        }
    }

    // --- INIT Settings ---
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
    }

    // --- INIT Profile ---
    private fun initProfile() {
        val auth = FirebaseAuth.getInstance()
        val tvUserTitle = findViewById<TextView>(R.id.tvUserTitle)
        val tvUserSub = findViewById<TextView>(R.id.tvUserSub)
        val btnLogin = findViewById<View>(R.id.btnLogin)
        val btnPremium = tabProfile.findViewById<View>(R.id.btnPremium)

        val user = auth.currentUser
        if (user != null) {
            tvUserTitle.text = user.email ?: "User"
            tvUserSub.text = "Logged In"
            btnLogin.setOnClickListener {
                auth.signOut()
                initProfile() // refresh
            }
        } else {
            tvUserTitle.text = "Guest User"
            tvUserSub.text = "Tap to Log In"
            btnLogin.setOnClickListener {
                startActivity(Intent(this, AuthActivity::class.java))
            }
        }

        btnPremium.setOnClickListener {
            startActivity(Intent(this, PremiumActivity::class.java))
        }

        setupMenuItem(R.id.btnTelegram, "Telegram Channel", android.R.drawable.ic_menu_send) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/PulseVPNForum")))
        }

        setupMenuItem(R.id.btnShare, "Share App", android.R.drawable.ic_menu_share) {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, "Hi, try PulseVPN. Follow the link and choose a plan that suits you best: \"https://pulsevpn.app\"")
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, "Share via"))
        }

        setupMenuItem(R.id.btnRate, "Rate Us", android.R.drawable.star_on) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://pulsevpn.app")))
        }

        setupMenuItem(R.id.btnAbout, "About", android.R.drawable.ic_menu_info_details) {
            android.widget.Toast.makeText(this, "PulseVPN v1.0", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupMenuItem(itemId: Int, title: String, iconResId: Int, onClick: () -> Unit) {
        val item = findViewById<View>(itemId)
        item.findViewById<TextView>(R.id.itemTitle).text = title
        item.findViewById<ImageView>(R.id.itemIcon).setImageResource(iconResId)
        item.setOnClickListener { onClick() }
    }

    // --- LIFECYCLE ---
    override fun onResume() {
        super.onResume()
        checkPremium()
        initProfile()
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

    // --- VPN Flow ---
    private fun fetchServerConfig() {
        FirebaseDatabase.getInstance().getReference("vpn_config").get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                serverIp = snapshot.child("ip").value.toString()
                serverPort = (snapshot.child("port").value as? Long)?.toInt() ?: 80
            }
        }
    }

    private fun checkPremium() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            FirebaseFirestore.getInstance().collection("users").document(user.uid).get()
                .addOnSuccessListener { doc -> isPremiumUser = doc.getBoolean("isPremium") ?: false }
        }
    }

    private fun handleToggle() {
        if (currentState == VpnState.CONNECTED) {
            disconnect()
        } else {
            if (isPremiumUser || SessionManager.isSessionActive(this)) {
                connect()
            } else {
                tvStatus.text = "LOADING AD..."
                AdManager.showAd(this) {
                    SessionManager.startNewSession(this)
                    connect()
                }
            }
        }
    }

    private fun connect() {
        currentState = VpnState.CONNECTING
        tvStatus.text = "CONNECTING..."
        tvStatus.setTextColor(colorAccent)
        ivPower.setColorFilter(colorAccent)
        ivPower.setImageResource(android.R.drawable.ic_lock_power_off)

        val intent = android.net.VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent) else startVpnService()

        handler.postDelayed({ setConnectedState() }, 2000)
    }

    private fun disconnect() {
        currentState = VpnState.DISCONNECTED
        tvStatus.text = "CONNECTION INACTIVE"
        tvStatus.setTextColor(colorInactive)
        ivPower.setColorFilter(Color.WHITE)
        ivPower.setImageResource(android.R.drawable.ic_lock_power_off)
        ivPower.animate().cancel()
        ivPower.rotation = 0f
        
        stopVpnService()
        connectionTimer?.cancel()
        tvTimer.text = "00:00:00"
    }

    private fun setConnectedState() {
        currentState = VpnState.CONNECTED
        tvStatus.text = "CONNECTION ACTIVE"
        tvStatus.setTextColor(colorCyan)
        ivPower.setColorFilter(colorCyan)
        ivPower.setImageResource(android.R.drawable.ic_lock_power_off)
        ivPower.animate().cancel()
        ivPower.rotation = 0f
        
        // Bouncing scale effect on connected
        btnMain.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200).withEndAction {
            btnMain.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
        }.start()

        startUiTimer()
    }

    private fun startUiTimer() {
        connectionTimer?.cancel()
        connectionTimer = object : CountDownTimer(3600000L, 1000) {
            var elapsed = 0L
            override fun onTick(ms: Long) {
                elapsed += 1000
                val h = elapsed / 1000 / 3600
                val m = (elapsed / 1000 / 60) % 60
                val s = (elapsed / 1000) % 60
                tvTimer.text = String.format("%02d:%02d:%02d", h, m, s)
            }
            override fun onFinish() { disconnect() }
        }.start()
    }

    private fun startVpnService() {
        val intent = Intent(this, MyVpnService::class.java).apply {
            action = "START"
            putExtra("SERVER_IP", serverIp)
            putExtra("SERVER_PORT", serverPort)
        }
        startService(intent)
    }

    private fun stopVpnService() {
        startService(Intent(this, MyVpnService::class.java).apply { action = "STOP" })
    }

    // --- Traffic Stats ---
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
            
            // Add total speed diff to graph
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