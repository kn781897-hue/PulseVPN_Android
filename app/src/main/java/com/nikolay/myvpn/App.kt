package com.nikolay.myvpn

import android.app.Application
import com.yandex.mobile.ads.common.MobileAds

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        MobileAds.initialize(this) {
            // SDK инициализирован
        }
    }
}