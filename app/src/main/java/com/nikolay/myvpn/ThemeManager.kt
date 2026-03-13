package com.nikolay.myvpn

import android.app.Activity
import android.content.Context

// Simplified ThemeManager — macOS dark mode only
object ThemeManager {

    fun isDarkMode(context: Context): Boolean = true // Always dark

    fun applyTheme(activity: Activity) {
        // Dark bg is set via XML and theme — no-op here
    }

    // Keep for legacy compatibility, but these are no-ops now
    fun applyTextColors(activity: Activity, vararg views: android.widget.TextView?) {}
    fun applySubTextColors(activity: Activity, vararg views: android.widget.TextView?) {}
    fun applyIconColors(activity: Activity, vararg views: android.widget.ImageView?) {}
    fun applyBtnColors(activity: Activity, vararg views: android.widget.ImageButton?) {}
    fun applyGlassColors(activity: Activity, vararg views: android.view.View?) {}
}