package com.nikolay.myvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import android.util.Base64
import java.security.SecureRandom


import libv2ray.Libv2ray
import libv2ray.CoreController
import libv2ray.CoreCallbackHandler
class MyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    private var coreController: CoreController? = null
    private val CHANNEL_ID = "MyVpnChannel"

    private var isPremium = false
    private val sessionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sessionRunnable = Runnable { stopVpn() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }

        isPremium = intent?.getBooleanExtra("IS_PREMIUM", false) ?: false

        // 1. Android требует, чтобы VPN работал с уведомлением
        val notification = createNotification()
        startForeground(1, notification)

        // 2. Запускаем VPN логику
        startVpn()

        return START_STICKY
    }

    private fun startVpn() {
        if (vpnInterface != null) return

        try {
            // А. Настраиваем сетевой интерфейс Android
            copyAssets()
            val builder = Builder()
            builder.setSession("My Xray VPN")
            builder.addAddress("10.0.1.1", 24)
            builder.addRoute("0.0.0.0", 0) // Перехватываем весь трафик
            builder.setMtu(1500)
            builder.addDnsServer("8.8.8.8")

            // === ВОТ ЭТА ВАЖНАЯ СТРОКА ===
            // Мы исключаем сами себя из туннеля, чтобы наше Ядро могло выйти в реальный интернет
            try {
                builder.addDisallowedApplication(packageName) //наше приложение(обязательно)

                // 1. Яндекс (Карты, Музыка, Кинопоиск) - чтобы работали быстро
                builder.addDisallowedApplication("ru.yandex.yandexmaps") // Карты
                builder.addDisallowedApplication("ru.yandex.yandexnavi") // Навигатор
                builder.addDisallowedApplication("ru.yandex.music")      // Музыка
                builder.addDisallowedApplication("ru.kinopoisk")         // Кинопоиск
                builder.addDisallowedApplication("ru.yandex.taxi")       // Такси

                // 2. Банки (чтобы не блокировали вход)
                builder.addDisallowedApplication("ru.sberbankmobile")       // Сбербанк
                builder.addDisallowedApplication("com.idamob.tinkoff.android") // Т-Банк (Тинькофф)
                builder.addDisallowedApplication("ru.alfabank.mobile.android") // Альфа-Банк
                builder.addDisallowedApplication("ru.vtb24.mobile")         // ВТБ

                // 3. Госуслуги и Соцсети РФ
                builder.addDisallowedApplication("ru.rostel")            // Госуслуги
                builder.addDisallowedApplication("com.vkontakte.android") // ВКонтакте
                builder.addDisallowedApplication("ru.ok.android")        // Одноклассники

                // 4. Магазины (Ozon, WB)
                builder.addDisallowedApplication("com.wildberries.ru")   // Wildberries
                builder.addDisallowedApplication("ru.ozon.app.android")  //озон

                // --- 5. Тесты скорости (Speedtest) ---
                builder.addDisallowedApplication("org.zwanoo.android.speedtest")

                // --- 6. Мессенджеры (чтобы работали ЗВОНКИ и видео) ---
                builder.addDisallowedApplication("org.telegram.messenger")    // Telegram (официальный)
                builder.addDisallowedApplication("org.thunderdog.challegram") // Telegram X
                builder.addDisallowedApplication("com.whatsapp")              // WhatsApp
                builder.addDisallowedApplication("com.whatsapp.w4b")          // WhatsApp Business
                builder.addDisallowedApplication("com.viber.voip")            // Viber

                // --- 7. Приложения для видеоконференций (они используют UDP) ---
                // Если их пустить через HTTP Proxy, они не подключатся к конференции
                builder.addDisallowedApplication("us.zoom.videomeetings")     // Zoom
                builder.addDisallowedApplication("com.discord")               // Discord
                builder.addDisallowedApplication("com.skype.raider")          // Skype
                builder.addDisallowedApplication("com.google.android.apps.meetings") // Google Meet

                // === 8. Мессенджер MAX (Российский) ===
                // Чтобы работали видеозвонки и не было задержек
                builder.addDisallowedApplication("ru.oneme.app")       // Основной пакет MAX
                builder.addDisallowedApplication("ru.vk.max")          // Возможный альтернативный пакет
                builder.addDisallowedApplication("com.vk.im")


            } catch (e: Exception) {
                // На редких старых Android может не сработать, но обычно работает
                e.printStackTrace()
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                builder.setHttpProxy(android.net.ProxyInfo.buildDirectProxy("127.0.0.1", 10809))
            }

            // Создаем интерфейс (File Descriptor)
            vpnInterface = builder.establish()

            // Б. Готовим конфиг для XRay (Вставьте ваш JSON сюда!)
            // ВАЖНО: inbound должен быть socks на порту 10808 (или как у вас настроено)
            val myConfig = """
            {
              "log": {
                "loglevel": "warning"
              },
              "inbounds": [
                {
                  "tag": "socks-in",
                  "port": 10808,
                  "listen": "127.0.0.1",
                  "protocol": "socks",
                  "settings": {
                    "auth": "noauth",
                    "udp": true
                  }
                },
                {
                  "tag": "http-in",
                  "port": 10809,
                  "listen": "127.0.0.1",
                  "protocol": "http",
                  "sniffing": {
                    "enabled": true,
                    "destOverride": ["http", "tls"]
                  }
                }
              ],
              "outbounds": [
                {
                  "tag": "proxy",
                  "protocol": "vmess",
                  "settings": {
                    "vnext": [
                      {
                        "address": "31.58.87.8",
                        "port": 80,
                        "users": [
                          {
                            "id": "5e071f0e-44a0-4c79-a62d-e60800772e1c",
                            "alterId": 0,
                            "security": "auto"
                          }
                        ]
                      }
                    ]
                  },
                  "streamSettings": {
                    "network": "ws",
                    "security": "none",
                    "wsSettings": {
                      "path": "/"
                    }
                  }
                },
                {
                  "tag": "direct",
                  "protocol": "freedom"
                },
                {
                  "tag": "block",
                  "protocol": "blackhole"
                }
              ],
              "routing": {
                "domainStrategy": "AsIs",
                "rules": [
                  { "type": "field", "outboundTag": "block", "ip": ["geoip:private"] }
                ]
              }
            }
            """.trimIndent()

            // В. Запускаем ядро (Новый способ)
            // 1. Генерируем 32 случайных байта
            val randomBytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(randomBytes)

            // 2. Кодируем в Base64 URL_SAFE (без + и /, вместо них - и _)
            // Также убираем отступы (NO_WRAP) и лишние символы "равно" (NO_PADDING)
            val deviceId = Base64.encodeToString(
                randomBytes,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

            Log.d("VPN", "Generated DeviceID (Safe): $deviceId")

            // 3. Инициализация
            Libv2ray.initCoreEnv(filesDir.absolutePath, deviceId)

            // 2. Создаем обратный вызов (Callback), чтобы ядро могло слать логи нам
            val callback = object : CoreCallbackHandler {
                override fun onEmitStatus(p0: Long, p1: String?): Long {
                    // Сюда приходят логи и статусы от ядра
                    Log.d("XrayStatus", p1 ?: "")
                    return 0
                }

                override fun startup(): Long {
                    Log.d("XrayCore", "Ядро запускается")
                    return 0
                }

                override fun shutdown(): Long {
                    Log.d("XrayCore", "Ядро остановлено")
                    return 0
                }
            }

            // 3. Создаем контроллер
            coreController = Libv2ray.newCoreController(callback)

            // 4. Запускаем (метод называется startLoop!)
            coreController?.startLoop(myConfig)

            sessionHandler.removeCallbacks(sessionRunnable) // На всякий случай очищаем старый
            if (!isPremium) {
                sessionHandler.removeCallbacks(sessionRunnable)
                sessionHandler.postDelayed(sessionRunnable, 3600000L) // 1 час
                Log.d("VPN", "Session timer started (Free User)")
            } else {
                Log.d("VPN", "Unlimited session started (Premium User)")
            }

            // Г. Запускаем Tun2Socks (встроенный в ядро или внешний)
            // Примечание: Если ваше ядро не умеет само забирать трафик из tun,
            // здесь нужен код, который пересылает пакеты из vpnInterface в 127.0.0.1:10808
            // Для начала проверим запуск самого ядра.

        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun stopVpn() {
        try {
            sessionHandler.removeCallbacks(sessionRunnable) //снимаем время

            // Метод называется stopLoop!
            coreController?.stopLoop()
            coreController = null

            vpnInterface?.close()
            vpnInterface = null

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotification(): Notification {
        // Создаем канал уведомлений (для Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Status",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, MyVpnService::class.java)
        stopIntent.action = "STOP"
        val pendingStopIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VPN Работает")
            .setContentText("Подключение к Xray активно")
            .setSmallIcon(R.mipmap.ic_launcher) // Убедитесь, что иконка существует
            .addAction(R.drawable.ic_launcher_foreground, "Остановить", pendingStopIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }

    private fun copyAssets() {
        val files = listOf("geoip.dat", "geosite.dat")
        for (fileName in files) {
            val file = File(filesDir, fileName)
            if (!file.exists()) {
                try {
                    assets.open(fileName).use { inputStream ->
                        java.io.FileOutputStream(file).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    Log.d("VPN", "Файл $fileName успешно скопирован")
                } catch (e: Exception) {
                    Log.e("VPN", "Ошибка копирования $fileName: ${e.message}")
                }
            }
        }
    }
}