package com.nikolay.myvpn

import android.content.Context

object SessionManager {
    private const val PREF_NAME = "VPN_TRIAL_SESSION"
    private const val KEY_TRIAL_STARTED = "trial_started"
    private const val KEY_TRIAL_EXPIRY = "trial_expiry_time"
    private const val KEY_NOTICE_SHOWN = "trial_notice_shown"
    const val TRIAL_DURATION_MS = 3600000L // 1 час

    /**
     * Запуск 1-часового триала при первом запуске / скачивании
     */
    fun startTrialIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_TRIAL_STARTED, false)) {
            val now = System.currentTimeMillis()
            prefs.edit()
                .putBoolean(KEY_TRIAL_STARTED, true)
                .putLong(KEY_TRIAL_EXPIRY, now + TRIAL_DURATION_MS)
                .apply()
        }
    }

    /**
     * Активен ли 1-часовой триал
     */
    fun isTrialActive(context: Context): Boolean {
        return getRemainingTime(context) > 0
    }

    /**
     * Был ли запущен триал когда-либо
     */
    fun isTrialStarted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_TRIAL_STARTED, false)
    }

    /**
     * Истек ли 1-часовой триал
     */
    fun isTrialExpired(context: Context): Boolean {
        return isTrialStarted(context) && getRemainingTime(context) <= 0
    }

    /**
     * Оставшееся время триала (в миллисекундах)
     */
    fun getRemainingTime(context: Context): Long {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val expiryTime = prefs.getLong(KEY_TRIAL_EXPIRY, 0L)
        val diff = expiryTime - System.currentTimeMillis()
        return if (diff > 0) diff else 0L
    }

    /**
     * Проверка, было ли показано уведомление о триале
     */
    fun hasShownNotice(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_NOTICE_SHOWN, false)
    }

    /**
     * Отметить, что уведомление о триале показано
     */
    fun markNoticeShown(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_NOTICE_SHOWN, true).apply()
    }

    // Совместимость
    fun isSessionActive(context: Context): Boolean {
        return isTrialActive(context)
    }
}