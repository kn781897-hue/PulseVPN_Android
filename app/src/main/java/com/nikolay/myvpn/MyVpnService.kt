package com.nikolay.myvpn

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.LondonX.tun2socks.Tun2Socks
import libv2ray.*
import java.io.File

class MyVpnService : VpnService() {

    companion object {
        const val PREFS_RUNNING = "VPN_RUNNING_STATE"
        const val KEY_IS_RUNNING = "is_vpn_running"
        const val KEY_START_TIME = "vpn_start_time"
        const val ACTION_STATE_CHANGED = "com.nikolay.myvpn.VPN_STATE_CHANGED"
        private const val FLAG_FILE = "vpn_active.flag"

        init {
            System.loadLibrary("tun2socks")
        }

        private fun getFlagFile(context: Context): File {
            return File(context.filesDir, FLAG_FILE)
        }

        fun isVpnActive(context: Context): Boolean {
            val flag = getFlagFile(context)
            if (!flag.exists()) {
                return false
            }

            // Проверяем, жив ли действительно сервис / процесс :vpn
            val isRunning = isServiceRunning(context)
            if (!isRunning) {
                try { flag.delete() } catch (e: Exception) {}
                val prefs = context.getSharedPreferences(PREFS_RUNNING, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_IS_RUNNING, false).commit()
                return false
            }
            return true
        }

        private fun isServiceRunning(context: Context): Boolean {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return true

                val processes = am.runningAppProcesses
                if (processes != null) {
                    val targetProcess = "${context.packageName}:vpn"
                    for (proc in processes) {
                        if (proc.processName == targetProcess) {
                            return true
                        }
                    }
                }

                @Suppress("DEPRECATION")
                val services = am.getRunningServices(Int.MAX_VALUE)
                if (services != null) {
                    val targetService = MyVpnService::class.java.name
                    for (service in services) {
                        if (service.service.className == targetService) {
                            return true
                        }
                    }
                }
            } catch (e: Exception) {
                return true
            }
            return false
        }

        fun getStartTime(context: Context): Long {
            val flag = getFlagFile(context)
            if (flag.exists()) {
                val time = try { flag.readText().trim().toLongOrNull() } catch (e: Exception) { null }
                if (time != null && time > 0) {
                    return time
                }
            }
            val prefs = context.getSharedPreferences(PREFS_RUNNING, Context.MODE_PRIVATE)
            return prefs.getLong(KEY_START_TIME, System.currentTimeMillis())
        }
    }

    private val TAG = "VPN"
    private val CHANNEL_ID = "vpn_channel"

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunThread: Thread? = null
    private var coreController: CoreController? = null

    @Volatile
    private var running = false

    private var activeServerIp = ""
    private var activeServerPort = 0
    private var activeUuid = "768b8f55-440a-4528-9cac-db50c91e50b1"
    private var activeProtocol = "vless"
    private var activeSecurity = "reality"
    private var activeNetwork = "tcp"
    private var activePath = ""
    private var activeSni = "pulsevpn.shop"
    private var activePublicKey = "QbXfKUUrYUHJhdbGrTPDBMt57AMRD17yYQMf3RDEMVI"
    private var activeShortId = "283b35fcf8fc"
    private var activeFingerprint = "firefox"
    private var activeFlow = "xtls-rprx-vision"

    // сервис

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }

        val config = ServerConfigManager.getConfig(this)
        activeServerIp = intent?.getStringExtra("SERVER_IP")?.takeIf { it.isNotBlank() } ?: config.ip
        val portExtra = intent?.getIntExtra("SERVER_PORT", 0) ?: 0
        activeServerPort = if (portExtra > 0) portExtra else config.port
        intent?.getStringExtra("SERVER_UUID")?.let { uid ->
            if (uid.isNotBlank()) activeUuid = uid
        }
        activeProtocol = intent?.getStringExtra("SERVER_PROTOCOL")?.takeIf { it.isNotBlank() } ?: config.protocol
        activeSecurity = intent?.getStringExtra("SERVER_SECURITY")?.takeIf { it.isNotBlank() } ?: config.security
        activeNetwork = intent?.getStringExtra("SERVER_NETWORK")?.takeIf { it.isNotBlank() } ?: config.network
        activePath = intent?.getStringExtra("SERVER_PATH")?.takeIf { it.isNotBlank() } ?: config.path
        activeSni = intent?.getStringExtra("SERVER_SNI")?.takeIf { it.isNotBlank() } ?: config.sni
        activePublicKey = intent?.getStringExtra("SERVER_PUBLIC_KEY")?.takeIf { it.isNotBlank() } ?: config.publicKey
        activeShortId = intent?.getStringExtra("SERVER_SHORT_ID")?.takeIf { it.isNotBlank() } ?: config.shortId
        activeFingerprint = intent?.getStringExtra("SERVER_FINGERPRINT")?.takeIf { it.isNotBlank() } ?: config.fingerprint
        activeFlow = intent?.getStringExtra("SERVER_FLOW")?.takeIf { it.isNotBlank() } ?: config.flow

        if (running) return START_STICKY

        startForeground(1, createNotification())
        Thread { startVpn() }.start()

        return START_STICKY
    }

    override fun onRevoke() {
        Log.d(TAG, "VPN revoked by system")
        stopInternal()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopInternal()
        super.onDestroy()
    }

    // старт

    private fun copyAssets() {
        val files = listOf("geoip.dat", "geosite.dat")
        for (fileName in files) {
            val file = File(filesDir, fileName)
            if (!file.exists() || file.length() == 0L) {
                try {
                    assets.open(fileName).use { input ->
                        java.io.FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Asset copied: $fileName (${file.length()} bytes)")
                } catch (e: Exception) {
                    Log.e(TAG, "Error copying $fileName: ${e.message}")
                }
            }
        }
    }

    private fun startVpn() {
        synchronized(this) {
            if (running) return
            running = true
        }

        try {
            copyAssets()

            try {
                Tun2Socks.initialize(this)
            } catch (e: Throwable) {
                Log.w(TAG, "Tun2Socks.initialize: ${e.message}")
            }

            try {
                val deviceId = SubscriptionManager.getDeviceId(this)
                Libv2ray.initCoreEnv(filesDir.absolutePath, deviceId)
            } catch (e: Throwable) {
                Log.w(TAG, "Libv2ray.initCoreEnv: ${e.message}")
            }

            val builder = Builder()
                .setSession("Pulse VPN")
                .setMtu(1280)
                .addAddress("10.0.1.1", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")

            builder.addDisallowedApplication(packageName)

            val pfd = builder.establish()
                ?: throw IllegalStateException("VPN interface null")

            vpnInterface = pfd

            coreController = Libv2ray.newCoreController(object : CoreCallbackHandler {
                override fun onEmitStatus(p0: Long, p1: String?) = 0L
                override fun startup() = 0L
                override fun shutdown() = 0L
            })

            coreController!!.startLoop(buildV2RayConfig())

            // Небольшая задержка, чтобы ядро Xray успело открыться и слушать порт 10808 перед tun2socks
            try {
                Thread.sleep(150)
            } catch (e: InterruptedException) {}

            tunThread = Thread {
                try {
                    Log.d(TAG, "tun2socks start")
                    Tun2Socks.startTun2Socks(
                        Tun2Socks.LogLevel.INFO,
                        pfd,
                        1280,
                        "127.0.0.1",
                        10808,
                        "10.0.1.2",
                        "",
                        "255.255.255.0",
                        true,
                        arrayListOf()
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "tun2socks error", e)
                } finally {
                    Log.d(TAG, "tun2socks exited")
                }
            }

            tunThread!!.start()

            // Сохраняем состояние для надежной синхронизации с UI и между процессами
            val startTime = System.currentTimeMillis()
            try {
                val flag = getFlagFile(this)
                flag.writeText(startTime.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Error writing vpn flag", e)
            }

            val prefs = getSharedPreferences(PREFS_RUNNING, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(KEY_IS_RUNNING, true)
                .putLong(KEY_START_TIME, startTime)
                .commit()
            sendBroadcast(Intent(ACTION_STATE_CHANGED).putExtra("RUNNING", true))

        } catch (e: Exception) {
            Log.e(TAG, "VPN start error", e)
            stopVpn()
        }
    }

    // стоп

    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN service gracefully")
        stopInternal()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 500)
    }

    private fun stopInternal() {
        synchronized(this) {
            if (!running) return
            running = false
        }

        try {
            getFlagFile(this).delete()
        } catch (e: Exception) {}

        // Сбрасываем сохраненное состояние
        val prefs = getSharedPreferences(PREFS_RUNNING, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_IS_RUNNING, false).commit()
        sendBroadcast(Intent(ACTION_STATE_CHANGED).putExtra("RUNNING", false))

        try {
            Log.d(TAG, "Stopping v2ray core")
            coreController?.stopLoop()
            coreController = null
        } catch (e: Exception) {
            Log.e(TAG, "v2ray stop error", e)
        }

        try {
            Log.d(TAG, "Closing VPN interface")
            vpnInterface?.close()   // закрытие TUN восстанавливает интернет
            vpnInterface = null
        } catch (e: Exception) {
            Log.e(TAG, "tun close error", e)
        }

        try {
            Tun2Socks.stopTun2Socks()
        } catch (e: Exception) {}

        tunThread = null
    }

    // уведы

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, MyVpnService::class.java).apply {
            action = "STOP"
        }

        val stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VPN активен")
            .setContentText("Подключение установлено")
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(0, "Отключить", stopPending)
            .build()
    }

    // конфиг

    private fun buildV2RayConfig(): String {
        val serverIp = activeServerIp
        val port = activeServerPort
        val uuid = activeUuid
        val protocol = activeProtocol.lowercase()
        val security = activeSecurity.lowercase()
        val network = activeNetwork.lowercase()
        val path = if (activePath.isNotBlank()) activePath else "/vpn"
        val sni = activeSni
        val pubKey = activePublicKey
        val shortId = activeShortId
        val fingerprint = activeFingerprint
        val flow = if (activeFlow.isNotBlank()) activeFlow else "xtls-rprx-vision"

        val proxyOutbound = if (protocol == "vless" && security == "reality") {
            """
            {
              "tag": "proxy",
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {
                    "address": "$serverIp",
                    "port": $port,
                    "users": [
                      {
                        "id": "$uuid",
                        "encryption": "none",
                        "flow": "$flow"
                      }
                    ]
                  }
                ]
              },
              "streamSettings": {
                "network": "tcp",
                "security": "reality",
                "realitySettings": {
                  "show": false,
                  "fingerprint": "$fingerprint",
                  "serverName": "$sni",
                  "publicKey": "$pubKey",
                  "shortId": "$shortId",
                  "spiderX": "/"
                },
                "tcpSettings": {
                  "header": {
                    "type": "none"
                  }
                }
              }
            }
            """.trimIndent()
        } else if (protocol == "vless") {
            // VLESS-WebSocket через Cloudflare CDN
            """
            {
              "tag": "proxy",
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {
                    "address": "$serverIp",
                    "port": $port,
                    "users": [
                      {
                        "id": "$uuid",
                        "encryption": "none"
                      }
                    ]
                  }
                ]
              },
              "streamSettings": {
                "network": "ws",
                "security": "tls",
                "tlsSettings": {
                  "serverName": "$sni",
                  "fingerprint": "chrome"
                },
                "wsSettings": {
                  "path": "$path",
                  "headers": {
                    "Host": "$sni"
                  }
                }
              }
            }
            """.trimIndent()
        } else {
            """
            {
              "tag": "proxy",
              "protocol": "vmess",
              "settings": {
                "vnext": [
                  {
                    "address": "$serverIp",
                    "port": $port,
                    "users": [
                      { "id": "$uuid", "alterId": 0 }
                    ]
                  }
                ]
              },
              "streamSettings": {
                "network": "ws",
                "wsSettings": {
                  "path": "/",
                  "headers": {
                    "Host": "pulsevpn.shop"
                  }
                }
              }
            }
            """.trimIndent()
        }

        return """
        {
          "log": { "loglevel": "warning" },
          "policy": {
            "system": {
              "statsOutboundDownlink": true,
              "statsInboundDownlink": true,
              "statsInboundUplink": true,
              "statsOutboundUplink": true
            }
          },
          "dns": {
            "servers": [
              "1.1.1.1",
              {
                "address": "1.1.1.1",
                "port": 53,
                "skipFallback": false
              },
              {
                "address": "8.8.8.8",
                "port": 53,
                "skipFallback": false
              }
            ],
            "disableCache": false,
            "disableFallback": false,
            "queryStrategy": "UseIPv4"
          },
          "inbounds": [
            {
              "tag": "socks",
              "port": 10808,
              "listen": "127.0.0.1",
              "protocol": "socks",
              "settings": {
                "udp": true
              },
              "sniffing": {
                "enabled": true,
                "destOverride": [
                  "quic",
                  "tls",
                  "http"
                ],
                "routeOnly": false
              }
            }
          ],
          "outbounds": [
            $proxyOutbound,
            {
              "tag": "block",
              "protocol": "blackhole",
              "settings": {
                "response": {
                  "type": "none"
                }
              }
            },
            {
              "tag": "direct",
              "protocol": "freedom",
              "settings": {}
            }
          ],
          "stats": {}
        }
        """.trimIndent()
    }
}