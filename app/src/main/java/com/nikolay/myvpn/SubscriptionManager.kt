package com.nikolay.myvpn

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object SubscriptionManager {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private const val PREFS_TG = "VPN_TELEGRAM_AUTH"
    private const val KEY_TG_KEY = "saved_telegram_key"
    private const val KEY_CUSTOM_UUID = "saved_custom_uuid"
    private const val KEY_EXPIRY_MS = "saved_expiry_ms"
    private const val KEY_TG_USERNAME = "saved_telegram_username"
    private const val KEY_IS_LOGGED_OUT = "is_logged_out"
    const val MAX_DEVICES = 3 // Ограничение: до 3 устройств

    fun isLoggedOut(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_TG, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_IS_LOGGED_OUT, false)
    }

    fun setLoggedOut(context: Context, loggedOut: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_TG, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_IS_LOGGED_OUT, loggedOut).apply()
    }

    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences("VPN_DEVICE_ID", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        if (deviceId == null) {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            deviceId = if (!androidId.isNullOrEmpty() && androidId != "9774d56d682e549c") {
                androidId
            } else {
                UUID.randomUUID().toString()
            }
            prefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }

    fun getSavedTelegramKey(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_TG, Context.MODE_PRIVATE)
        return prefs.getString(KEY_TG_KEY, null)
    }

    fun saveTelegramKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS_TG, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TG_KEY, key.trim()).apply()
    }

    fun getTelegramUsername(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_TG, Context.MODE_PRIVATE)
        return prefs.getString(KEY_TG_USERNAME, null)
    }

    fun saveTelegramUsername(context: Context, username: String) {
        val prefs = context.getSharedPreferences(PREFS_TG, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TG_USERNAME, username.trim().removePrefix("@")).apply()
    }

    fun getCustomUuid(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_TG, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CUSTOM_UUID, null)
    }

    fun saveCustomUuid(context: Context, uuid: String) {
        val prefs = context.getSharedPreferences(PREFS_TG, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_UUID, uuid.trim()).apply()
    }

    fun clearTelegramKey(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_TG, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_TG_KEY)
            .remove(KEY_CUSTOM_UUID)
            .remove(KEY_EXPIRY_MS)
            .remove(KEY_TG_USERNAME)
            .apply()
    }

    fun openTelegramLink(context: Context) {
        setLoggedOut(context, false)
        val uid = auth.currentUser?.uid ?: getDeviceId(context)
        val botUrl = "https://t.me/PullseVpn_bot?start=link_$uid"
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(botUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            ToastHelper.show(context, "Не удалось открыть Telegram", ToastHelper.Type.ERROR)
        }
    }

    // Автоматическая фоновая синхронизация с Firestore при возвращении из Telegram / старте
    fun syncWithFirestore(context: Context, onComplete: ((synced: Boolean) -> Unit)? = null) {
        val user = auth.currentUser
        val deviceId = getDeviceId(context)

        // Если пользователь явно нажал "Выйти" и не авторизован через Firebase Auth, не восстанавливаем сессию по deviceId
        if (user == null && isLoggedOut(context)) {
            onComplete?.invoke(false)
            return
        }

        fun processDoc(data: Map<String, Any?>) {
            val subId = (data["sub_id"] as? String)?.trim()
            val tgUser = (data["telegram_username"] as? String)?.trim()
            val expiry = (data["subscriptionExpiry"] as? Number)?.toLong() ?: 0L

            if (!tgUser.isNullOrBlank()) {
                saveTelegramUsername(context, tgUser)
            }
            if (!subId.isNullOrBlank()) {
                saveTelegramKey(context, subId)
                if (expiry > 0) {
                    context.getSharedPreferences(PREFS_TG, Context.MODE_PRIVATE)
                        .edit().putLong(KEY_EXPIRY_MS, expiry).apply()
                }
                setLoggedOut(context, false)
                // Запрашиваем конфиг для извлечения серверного UUID
                checkXuiSubscription(subId) { _, _, extractedUuid ->
                    if (!extractedUuid.isNullOrEmpty()) {
                        saveCustomUuid(context, extractedUuid)
                    }
                    onComplete?.invoke(true)
                }
                return
            }
            onComplete?.invoke(false)
        }

        if (user != null) {
            db.collection("users").document(user.uid).get()
                .addOnSuccessListener { doc ->
                    if (doc != null && doc.exists() && doc.data != null) {
                        processDoc(doc.data!!)
                    } else {
                        db.collection("users").whereEqualTo("deviceId", deviceId).limit(1).get()
                            .addOnSuccessListener { qs ->
                                if (!qs.isEmpty) processDoc(qs.documents[0].data ?: emptyMap())
                                else onComplete?.invoke(false)
                            }
                            .addOnFailureListener { onComplete?.invoke(false) }
                    }
                }
                .addOnFailureListener { onComplete?.invoke(false) }
        } else {
            db.collection("users").whereEqualTo("deviceId", deviceId).limit(1).get()
                .addOnSuccessListener { qs ->
                    if (!qs.isEmpty) processDoc(qs.documents[0].data ?: emptyMap())
                    else onComplete?.invoke(false)
                }
                .addOnFailureListener { onComplete?.invoke(false) }
        }
    }

    fun isCachedPremium(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_TG, Context.MODE_PRIVATE)
        val savedExpiry = prefs.getLong(KEY_EXPIRY_MS, 0L)
        return savedExpiry > System.currentTimeMillis()
    }

    fun saveCachedExpiry(context: Context, expiryMs: Long) {
        val prefs = context.getSharedPreferences(PREFS_TG, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_EXPIRY_MS, expiryMs).apply()
    }

    // Проверка статуса подписки (Firebase Auth + Telegram Ключ / Сервер подписок)
    fun checkSubscriptionStatus(context: Context, onComplete: (isPremium: Boolean) -> Unit) {
        // Сначала синхронизируем данные из Firestore на случай, если пользователь привязал TG
        syncWithFirestore(context) {
            // 1. Проверяем Firebase Auth
            val user = auth.currentUser
            if (user != null) {
                db.collection("users").document(user.uid).get()
                    .addOnSuccessListener { document ->
                        if (document != null && document.exists()) {
                            val expiryDate = document.getLong("subscriptionExpiry") ?: 0L
                            val currentTime = System.currentTimeMillis()
                            if (expiryDate > currentTime) {
                                saveCachedExpiry(context, expiryDate)
                                onComplete(true)
                                return@addOnSuccessListener
                            }
                        }
                        checkTelegramKeyStatus(context, onComplete)
                    }
                    .addOnFailureListener {
                        checkTelegramKeyStatus(context, onComplete)
                    }
            } else {
                checkTelegramKeyStatus(context, onComplete)
            }
        }
    }

    private fun checkTelegramKeyStatus(context: Context, onComplete: (isPremium: Boolean) -> Unit) {
        val key = getSavedTelegramKey(context)
        if (key.isNullOrBlank()) {
            onComplete(false)
            return
        }

        // Проверяем локальный кэш экспирации если есть
        val prefs = context.getSharedPreferences(PREFS_TG, Context.MODE_PRIVATE)
        val savedExpiry = prefs.getLong(KEY_EXPIRY_MS, 0L)
        if (savedExpiry > 0 && System.currentTimeMillis() > savedExpiry) {
            onComplete(false)
            return
        }

        // Запрашиваем актуальный статус подписки с сервера XUI / бота
        val subId = extractSubId(key)
        checkXuiSubscription(subId) { success, expiryMs, _ ->
            if (success) {
                if (expiryMs > 0) {
                    prefs.edit().putLong(KEY_EXPIRY_MS, expiryMs).apply()
                }
                onComplete(true)
            } else {
                // Если с сервера подписок не вышло, проверяем Firestore
                checkFirestoreKey(context, key, onComplete)
            }
        }
    }

    private fun checkFirestoreKey(context: Context, key: String, onComplete: (isPremium: Boolean) -> Unit) {
        val deviceId = getDeviceId(context)

        db.collection("telegram_keys").document(key).get()
            .addOnSuccessListener { doc ->
                if (doc != null && doc.exists()) {
                    val isActive = doc.getBoolean("isActive") ?: true
                    val expiry = doc.getLong("subscriptionExpiry") ?: 0L
                    val devices = (doc.get("devices") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

                    val isNotExpired = expiry == 0L || expiry > System.currentTimeMillis()
                    val isDeviceBound = devices.contains(deviceId)

                    onComplete(isActive && isNotExpired && isDeviceBound)
                } else {
                    onComplete(false)
                }
            }
            .addOnFailureListener {
                onComplete(false)
            }
    }

    // Извлечение чистого sub_id из введенного текста или полной ссылки
    fun extractSubId(input: String): String {
        var str = input.trim()
        if (str.contains("/getsub/")) {
            str = str.substringAfter("/getsub/")
        } else if (str.contains("/connect/")) {
            str = str.substringAfter("/connect/")
        }
        if (str.contains("?")) {
            str = str.substringBefore("?")
        }
        if (str.contains("/")) {
            str = str.substringBefore("/")
        }
        return str.trim()
    }

    // Активация ключа / подписки из Telegram с лимитом до 3 устройств
    fun activateTelegramKey(
        context: Context,
        rawKey: String,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        val cleanKey = extractSubId(rawKey)
        if (cleanKey.isEmpty()) {
            onResult(false, "Введите ключ или ссылку подписки")
            return
        }

        val deviceId = getDeviceId(context)

        // 1. Сначала пробуем проверить через сервер подписок бота PulseVPN
        checkXuiSubscription(cleanKey) { success, expiryMs, extractedUuid ->
            if (success) {
                // Сервер подписок подтвердил ключ! Регистрируем устройство с лимитом до 3
                bindDeviceToSubId(context, cleanKey, deviceId, expiryMs, extractedUuid, onResult)
            } else {
                // 2. Если это не subId, проверяем Firestore telegram_keys
                checkFirestoreKeyActivation(context, cleanKey, deviceId, onResult)
            }
        }
    }

    // Проверка sub_id через сервер подписок PulseVPN (https://sub.pulsevpn.shop/getsub/)
    private fun checkXuiSubscription(
        subId: String,
        callback: (success: Boolean, expiryMs: Long, extractedUuid: String?) -> Unit
    ) {
        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val url = "https://sub.pulsevpn.shop/getsub/$subId"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "PulseVPN-Android")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    callback(false, 0L, null)
                    return@Thread
                }

                val body = response.body?.string() ?: ""
                val userinfo = response.header("Subscription-Userinfo") ?: ""

                // Проверяем expire в заголовках
                var expireMs = 0L
                if (userinfo.contains("expire=")) {
                    val expSec = userinfo.substringAfter("expire=").substringBefore(";").trim().toLongOrNull() ?: 0L
                    if (expSec > 0) {
                        expireMs = expSec * 1000L
                        if (System.currentTimeMillis() > expireMs) {
                            // Срок истек
                            callback(false, expireMs, null)
                            return@Thread
                        }
                    }
                }

                // Декодируем base64 контент, чтобы достать UUID пользователя
                var userUuid: String? = null
                try {
                    val decodedLines = String(Base64.decode(body.trim(), Base64.DEFAULT))
                    for (line in decodedLines.split("\n")) {
                        if (line.startsWith("vmess://")) {
                            val vmessJsonStr = String(Base64.decode(line.removePrefix("vmess://").trim(), Base64.DEFAULT))
                            val vmessJson = JSONObject(vmessJsonStr)
                            userUuid = vmessJson.optString("id")
                            if (!userUuid.isNullOrEmpty()) break
                        } else if (line.startsWith("vless://")) {
                            val uuidPart = line.removePrefix("vless://").substringBefore("@")
                            if (uuidPart.isNotEmpty()) {
                                userUuid = uuidPart
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Игнорируем ошибку парсинга vmess
                }

                callback(true, expireMs, userUuid)
            } catch (e: Exception) {
                callback(false, 0L, null)
            }
        }.start()
    }

    private fun bindDeviceToSubId(
        context: Context,
        subId: String,
        deviceId: String,
        expiryMs: Long,
        extractedUuid: String?,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        val docRef = db.collection("sub_devices").document(subId)
        docRef.get().addOnSuccessListener { snapshot ->
            val devices = (snapshot.get("devices") as? List<*>)?.mapNotNull { it?.toString() }?.toMutableList() ?: mutableListOf()

            if (devices.contains(deviceId)) {
                // Уже авторизован на этом устройстве
                setLoggedOut(context, false)
                saveTelegramKey(context, subId)
                if (!extractedUuid.isNullOrEmpty()) saveCustomUuid(context, extractedUuid)
                if (expiryMs > 0) context.getSharedPreferences(PREFS_TG, Context.MODE_PRIVATE).edit().putLong(KEY_EXPIRY_MS, expiryMs).apply()
                onResult(true, "Устройство авторизовано (${devices.size}/$MAX_DEVICES)")
                return@addOnSuccessListener
            }

            if (devices.size >= MAX_DEVICES) {
                onResult(false, "Достигнут лимит устройств (максимум $MAX_DEVICES устройства на 1 подписку)")
                return@addOnSuccessListener
            }

            // Добавляем устройство
            docRef.set(hashMapOf("devices" to FieldValue.arrayUnion(deviceId)), SetOptions.merge())
                .addOnSuccessListener {
                    setLoggedOut(context, false)
                    saveTelegramKey(context, subId)
                    if (!extractedUuid.isNullOrEmpty()) saveCustomUuid(context, extractedUuid)
                    if (expiryMs > 0) context.getSharedPreferences(PREFS_TG, Context.MODE_PRIVATE).edit().putLong(KEY_EXPIRY_MS, expiryMs).apply()
                    val newCount = devices.size + 1
                    onResult(true, "Подписка привязана! Устройств: $newCount/$MAX_DEVICES")
                }
                .addOnFailureListener { e ->
                    // Если ошибка сохранения в Firestore, всё равно пускаем локально
                    setLoggedOut(context, false)
                    saveTelegramKey(context, subId)
                    if (!extractedUuid.isNullOrEmpty()) saveCustomUuid(context, extractedUuid)
                    onResult(true, "Подписка активирована!")
                }
        }.addOnFailureListener {
            // Если документ еще не создан или ошибка сети
            setLoggedOut(context, false)
            saveTelegramKey(context, subId)
            if (!extractedUuid.isNullOrEmpty()) saveCustomUuid(context, extractedUuid)
            onResult(true, "Подписка активирована!")
        }
    }

    private fun checkFirestoreKeyActivation(
        context: Context,
        key: String,
        deviceId: String,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        db.collection("telegram_keys").document(key).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    db.collection("users").whereEqualTo("telegramKey", key).limit(1).get()
                        .addOnSuccessListener { querySnapshot ->
                            if (!querySnapshot.isEmpty) {
                                val userDoc = querySnapshot.documents[0]
                                bindDeviceToDoc(context, userDoc.reference, key, deviceId, onResult)
                            } else {
                                onResult(false, "Ключ не найден. Проверьте правильность или получите в боте")
                            }
                        }
                        .addOnFailureListener { e ->
                            onResult(false, "Ошибка проверки: ${e.localizedMessage}")
                        }
                    return@addOnSuccessListener
                }

                bindDeviceToDoc(context, doc.reference, key, deviceId, onResult)
            }
            .addOnFailureListener { e ->
                onResult(false, "Ошибка сети: ${e.localizedMessage}")
            }
    }

    private fun bindDeviceToDoc(
        context: Context,
        docRef: com.google.firebase.firestore.DocumentReference,
        key: String,
        deviceId: String,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        docRef.get().addOnSuccessListener { snapshot ->
            val isActive = snapshot.getBoolean("isActive") ?: true
            val expiry = snapshot.getLong("subscriptionExpiry") ?: 0L
            val devices = (snapshot.get("devices") as? List<*>)?.mapNotNull { it?.toString() }?.toMutableList() ?: mutableListOf()

            if (!isActive) {
                onResult(false, "Этот ключ заблокирован")
                return@addOnSuccessListener
            }

            if (expiry > 0 && System.currentTimeMillis() > expiry) {
                onResult(false, "Срок действия подписки по этому ключу истек")
                return@addOnSuccessListener
            }

            if (devices.contains(deviceId)) {
                setLoggedOut(context, false)
                saveTelegramKey(context, key)
                onResult(true, "Устройство успешно авторизовано (${devices.size}/$MAX_DEVICES)")
                return@addOnSuccessListener
            }

            if (devices.size >= MAX_DEVICES) {
                onResult(false, "Достигнут лимит устройств (максимум $MAX_DEVICES устройства на аккаунт)")
                return@addOnSuccessListener
            }

            docRef.update("devices", FieldValue.arrayUnion(deviceId))
                .addOnSuccessListener {
                    setLoggedOut(context, false)
                    saveTelegramKey(context, key)
                    val newCount = devices.size + 1
                    onResult(true, "Ключ успешно активирован! (Устройств: $newCount/$MAX_DEVICES)")
                }
                .addOnFailureListener { e ->
                    onResult(false, "Не удалось привязать устройство: ${e.localizedMessage}")
                }
        }
    }

    // Отвязка текущего устройства (выход)
    fun unlinkDevice(context: Context, onComplete: (Boolean) -> Unit) {
        setLoggedOut(context, true)
        val key = getSavedTelegramKey(context)
        val deviceId = getDeviceId(context)

        clearTelegramKey(context)

        // Очищаем deviceId в коллекции users Firestore, чтобы сессия не восстанавливалась
        try {
            db.collection("users").whereEqualTo("deviceId", deviceId).get()
                .addOnSuccessListener { qs ->
                    for (doc in qs.documents) {
                        try {
                            doc.reference.update("deviceId", FieldValue.delete())
                        } catch (e: Exception) {}
                    }
                }
        } catch (e: Exception) {}

        val uid = auth.currentUser?.uid
        if (uid != null) {
            try {
                db.collection("users").document(uid).update("deviceId", FieldValue.delete())
            } catch (e: Exception) {}
        }

        if (key.isNullOrBlank()) {
            onComplete(true)
            return
        }

        val subId = extractSubId(key)
        try {
            db.collection("sub_devices").document(subId)
                .update("devices", FieldValue.arrayRemove(deviceId))
                .addOnCompleteListener { }
        } catch (e: Exception) {}

        try {
            db.collection("telegram_keys").document(key)
                .update("devices", FieldValue.arrayRemove(deviceId))
                .addOnCompleteListener {
                    onComplete(true)
                }
        } catch (e: Exception) {
            onComplete(true)
        }
    }
}