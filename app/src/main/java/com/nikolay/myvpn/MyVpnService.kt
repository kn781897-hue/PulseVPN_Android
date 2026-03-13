package com.nikolay.myvpn

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.LondonX.tun2socks.Tun2Socks
import libv2ray.*

class MyVpnService : VpnService() {

    companion object {
        init {
            System.loadLibrary("tun2socks")
        }
    }

    private val TAG = "VPN"
    private val CHANNEL_ID = "vpn_channel"

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunThread: Thread? = null
    private var coreController: CoreController? = null

    @Volatile
    private var running = false

    // ===================== SERVICE =====================

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }

        if (running) return START_STICKY

        startForeground(1, createNotification())
        Thread { startVpn() }.start()

        return START_STICKY
    }

    override fun onRevoke() {
        Log.d(TAG, "VPN revoked by system")
        stopInternal()
        super.onRevoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        // НИЧЕГО не делаем
    }

    // ===================== START =====================

    private fun startVpn() {
        synchronized(this) {
            if (running) return
            running = true
        }

        try {
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

        } catch (e: Exception) {
            Log.e(TAG, "VPN start error", e)
            stopVpn()
        }
    }

    // ===================== STOP =====================

    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN service gracefully")
        stopInternal()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        // ❗ Чтобы избежать Segmentation Fault в нативной библиотеке tun2socks
        // и полностью освободить ресурсы, завершаем процесс сервиса с кодом 0.
        // Так как сервис в отдельном процессе (:vpn), это не затронет основное приложение.
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
            Log.d(TAG, "Stopping v2ray core")
            coreController?.stopLoop()
            coreController = null
        } catch (e: Exception) {
            Log.e(TAG, "v2ray stop error", e)
        }

        try {
            Log.d(TAG, "Closing VPN interface")
            vpnInterface?.close()   // ← закрытие TUN восстанавливает интернет
            vpnInterface = null
        } catch (e: Exception) {
            Log.e(TAG, "tun close error", e)
        }

        tunThread = null
    }

    // ===================== NOTIFICATION =====================

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

    // ===================== CONFIG =====================

    private fun buildV2RayConfig(): String {
        val serverIp = "31.58.87.8"
        val port = 80
        val uuid = "768b8f55-440a-4528-9cac-db50c91e50b1"

        return """
        {
          "log": { "loglevel": "none" },
          "inbounds": [
            {
              "port": 10808,
              "listen": "127.0.0.1",
              "protocol": "socks",
              "settings": { "udp": true }
            }
          ],
          "outbounds": [
            {
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
                "wsSettings": { "path": "/" }
              }
            }
          ]
        }
        """.trimIndent()
    }
}
