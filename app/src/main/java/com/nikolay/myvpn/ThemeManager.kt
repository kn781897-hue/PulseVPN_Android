package com.nikolay.myvpn

import android.app.Activity
import android.content.Context

object ThemeManager {

    fun isDarkMode(context: Context): Boolean = true

    fun applyTheme(activity: Activity) {

    }

    fun applyTextColors(activity: Activity, vararg views: android.widget.TextView?) {}
    fun applySubTextColors(activity: Activity, vararg views: android.widget.TextView?) {}
    fun applyIconColors(activity: Activity, vararg views: android.widget.ImageView?) {}
    fun applyBtnColors(activity: Activity, vararg views: android.widget.ImageButton?) {}
    fun applyGlassColors(activity: Activity, vararg views: android.view.View?) {}
}