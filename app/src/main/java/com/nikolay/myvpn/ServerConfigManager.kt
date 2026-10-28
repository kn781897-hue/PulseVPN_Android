package com.nikolay.myvpn

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ServerConfig(
    val ip: String = "sub.pulsevpn.shop",
    val port: Int = 443,
    val protocol: String = "vless",
    val security: String = "tls",
    val network: String = "ws",
    val path: String = "/vpn",
    val sni: String = "sub.pulsevpn.shop",
    val publicKey: String = "QbXfKUUrYUHJhdbGrTPDBMt57AMRD17yYQMf3RDEMVI",
    val shortId: String = "283b35fcf8fc",
    val fingerprint: String = "chrome",
    val flow: String = "xtls-rprx-vision"
)

object ServerConfigManager {

    private const val TAG = "ServerConfigManager"
    private const val PREFS_NAME = "VPN_SERVER_CONFIG"

    private const val KEY_IP = "server_ip"
    private const val KEY_PORT = "server_port"
    private const val KEY_PROTOCOL = "server_protocol"
    private const val KEY_SECURITY = "server_security"
    private const val KEY_NETWORK = "server_network"
    private const val KEY_PATH = "server_path"
    private const val KEY_SNI = "server_sni"
    private const val KEY_PUBLIC_KEY = "server_public_key"
    private const val KEY_SHORT_ID = "server_short_id"
    private const val KEY_FINGERPRINT = "server_fingerprint"
    private const val KEY_FLOW = "server_flow"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var listenerRegistration: ListenerRegistration? = null

    fun getConfig(context: Context): ServerConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val default = ServerConfig()
        return ServerConfig(
            ip = prefs.getString(KEY_IP, default.ip) ?: default.ip,
            port = prefs.getInt(KEY_PORT, default.port),
            protocol = prefs.getString(KEY_PROTOCOL, default.protocol) ?: default.protocol,
            security = prefs.getString(KEY_SECURITY, default.security) ?: default.security,
            network = prefs.getString(KEY_NETWORK, default.network) ?: default.network,
            path = prefs.getString(KEY_PATH, default.path) ?: default.path,
            sni = prefs.getString(KEY_SNI, default.sni) ?: default.sni,
            publicKey = prefs.getString(KEY_PUBLIC_KEY, default.publicKey) ?: default.publicKey,
            shortId = prefs.getString(KEY_SHORT_ID, default.shortId) ?: default.shortId,
            fingerprint = prefs.getString(KEY_FINGERPRINT, default.fingerprint) ?: default.fingerprint,
            flow = prefs.getString(KEY_FLOW, default.flow) ?: default.flow
        )
    }

    fun saveConfig(context: Context, config: ServerConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_IP, config.ip)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_PROTOCOL, config.protocol)
            .putString(KEY_SECURITY, config.security)
            .putString(KEY_NETWORK, config.network)
            .putString(KEY_PATH, config.path)
            .putString(KEY_SNI, config.sni)
            .putString(KEY_PUBLIC_KEY, config.publicKey)
            .putString(KEY_SHORT_ID, config.shortId)
            .putString(KEY_FINGERPRINT, config.fingerprint)
            .putString(KEY_FLOW, config.flow)
            .apply()
        Log.d(TAG, "Server config saved: IP=${config.ip}, Port=${config.port}, Protocol=${config.protocol}, Security=${config.security}")
    }

    /**
     * Подключает real-time слушатель к Firestore (коллекция 'servers', документ 'current').
     * При смене IP администратором в Telegram-боте или веб-панели, конфигурация обновляется мгновенно.
     */
    fun startListening(context: Context, onConfigUpdated: ((ServerConfig) -> Unit)? = null) {
        if (listenerRegistration != null) return

        try {
            val db = FirebaseFirestore.getInstance()
            listenerRegistration = db.collection("servers").document("current")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Listen failed, attempting fallback API", error)
                        fetchFromFallbackApi(context, onConfigUpdated)
                        return@addSnapshotListener
                    }

                    if (snapshot != null && snapshot.exists()) {
                        val current = getConfig(context)
                        val newIp = snapshot.getString("ip") ?: current.ip
                        val newPort = snapshot.getLong("port")?.toInt() ?: current.port
                        val newProtocol = snapshot.getString("protocol") ?: current.protocol
                        val newSecurity = snapshot.getString("security") ?: current.security
                        val newNetwork = snapshot.getString("network") ?: current.network
                        val newPath = snapshot.getString("path") ?: current.path
                        val newSni = snapshot.getString("sni") ?: current.sni
                        val newPubKey = snapshot.getString("publicKey") ?: current.publicKey
                        val newShortId = snapshot.getString("shortId") ?: current.shortId
                        val newFingerprint = snapshot.getString("fingerprint") ?: current.fingerprint
                        val newFlow = snapshot.getString("flow") ?: current.flow

                        val newConfig = ServerConfig(
                            ip = newIp,
                            port = newPort,
                            protocol = newProtocol,
                            security = newSecurity,
                            network = newNetwork,
                            path = newPath,
                            sni = newSni,
                            publicKey = newPubKey,
                            shortId = newShortId,
                            fingerprint = newFingerprint,
                            flow = newFlow
                        )

                        saveConfig(context, newConfig)
                        onConfigUpdated?.invoke(newConfig)
                    } else {
                        // Если документ еще не создан в Firestore, загружаем с резервного API
                        fetchFromFallbackApi(context, onConfigUpdated)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Firestore listener: ${e.message}")
            fetchFromFallbackApi(context, onConfigUpdated)
        }
    }

    fun stopListening() {
        listenerRegistration?.remove()
        listenerRegistration = null
    }

    /**
     * Резервный опрос через HTTPS API на случай блокировок Firebase.
     */
    fun fetchFromFallbackApi(context: Context, onConfigUpdated: ((ServerConfig) -> Unit)? = null) {
        Thread {
            try {
                val request = Request.Builder()
                    .url("https://pulsevpn.shop/api/config.json")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string()
                    if (!bodyStr.isNullOrBlank()) {
                        val json = JSONObject(bodyStr)
                        val current = getConfig(context)
                        val newConfig = ServerConfig(
                            ip = json.optString("ip", current.ip),
                            port = json.optInt("port", current.port),
                            protocol = json.optString("protocol", current.protocol),
                            security = json.optString("security", current.security),
                            network = json.optString("network", current.network),
                            path = json.optString("path", current.path),
                            sni = json.optString("sni", current.sni),
                            publicKey = json.optString("publicKey", current.publicKey),
                            shortId = json.optString("shortId", current.shortId),
                            fingerprint = json.optString("fingerprint", current.fingerprint),
                            flow = json.optString("flow", current.flow)
                        )
                        saveConfig(context, newConfig)
                        onConfigUpdated?.invoke(newConfig)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fallback API error: ${e.message}")
            }
        }.start()
    }
}
