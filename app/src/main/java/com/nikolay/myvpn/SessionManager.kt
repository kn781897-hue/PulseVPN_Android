package com.nikolay.myvpn

import android.content.Context

object SessionManager {
    private const val PREF_NAME = "VPN_SESSION"
    private const val KEY_EXPIRY_TIME = "expiry_time"
    private const val ONE_HOUR_MS = 3600000L

    // Начинаем новую сессию (после просмотра рекламы)
    fun startNewSession(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val newExpiryTime = System.currentTimeMillis() + ONE_HOUR_MS
        prefs.edit().putLong(KEY_EXPIRY_TIME, newExpiryTime).apply()
    }

    // Проверяем, активна ли сессия
    fun isSessionActive(context: Context): Boolean {
        return getRemainingTime(context) > 0
    }

    // Сколько времени осталось (в миллисекундах)
    fun getRemainingTime(context: Context): Long {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val expiryTime = prefs.getLong(KEY_EXPIRY_TIME, 0)
        val currentTime = System.currentTimeMillis()

        val diff = expiryTime - currentTime
        return if (diff > 0) diff else 0
    }

    // Принудительно завершить сессию (если нужно)
    fun expireSession(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_EXPIRY_TIME, 0).apply()
    }
}