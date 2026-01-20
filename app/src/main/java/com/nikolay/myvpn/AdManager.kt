package com.nikolay.myvpn

import android.app.Activity
import android.util.Log
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.AdRequestConfiguration
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.interstitial.InterstitialAd
import com.yandex.mobile.ads.interstitial.InterstitialAdEventListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoadListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoader

object AdManager {

    private var interstitialAd: InterstitialAd? = null
    private var loader: InterstitialAdLoader? = null

    // Тестовый ID для межстраничной рекламы (Demo)
    // В релизе замените на свой реальный ID из кабинета Яндекса!
    private const val AD_UNIT_ID = "R-M-DEMO-interstitial"

    fun loadAd(context: Activity) {
        // Создаем загрузчик
        loader = InterstitialAdLoader(context).apply {
            setAdLoadListener(object : InterstitialAdLoadListener {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    Log.d("YandexAds", "Ad Loaded successfully")
                }

                override fun onAdFailedToLoad(error: AdRequestError) {
                    // Здесь используется AdRequestError
                    Log.e("YandexAds", "Ad Failed to Load: ${error.description}")
                    interstitialAd = null
                }
            })
        }

        // === ГЛАВНОЕ ИЗМЕНЕНИЕ ДЛЯ SDK v7+ ===
        // Создаем конфигурацию запроса
        val adRequestConfiguration = AdRequestConfiguration.Builder(AD_UNIT_ID).build()

        // Загружаем рекламу
        loader?.loadAd(adRequestConfiguration)
    }

    fun showAd(activity: Activity, onAdClosed: () -> Unit) {
        if (interstitialAd != null) {
            interstitialAd?.setAdEventListener(object : InterstitialAdEventListener {
                override fun onAdDismissed() {
                    Log.d("YandexAds", "Ad Dismissed")
                    // Реклама закрыта -> очищаем и грузим новую
                    interstitialAd = null
                    loadAd(activity)
                    // Выполняем действие (подключение VPN)
                    onAdClosed()
                }

                override fun onAdFailedToShow(adError: AdError) {
                    // Здесь используется AdError
                    Log.e("YandexAds", "Ad Failed to Show: ${adError.description}")
                    interstitialAd = null
                    // Если ошибка показа, все равно пускаем пользователя
                    onAdClosed()
                }

                override fun onAdShown() {
                    Log.d("YandexAds", "Ad Shown")
                }

                override fun onAdClicked() {
                    Log.d("YandexAds", "Ad Clicked")
                }

                override fun onAdImpression(data: ImpressionData?) {}
            })

            interstitialAd?.show(activity)
        } else {
            // Если реклама еще не загрузилась
            Log.d("YandexAds", "Ad not ready yet, skipping")
            onAdClosed()
            // Пробуем загрузить на следующий раз
            loadAd(activity)
        }
    }
}