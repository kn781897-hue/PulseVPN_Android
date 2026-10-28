package com.nikolay.myvpn

import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.core.view.WindowCompat
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class PremiumActivity : AppCompatActivity() {

    private lateinit var plan1: LinearLayout
    private lateinit var plan3: LinearLayout
    private lateinit var plan12: LinearLayout

    // По умолчанию 1 год (1490 руб)
    private var selectedAmount = 1490
    private var selectedPeriod = "1 Год"

    private var lastClickTime: Long = 0

    private val SERVER_URL = "https://pulsevpn.shop/create-payment"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        setContentView(R.layout.activity_premium)

        findViewById<View>(R.id.btnBack).setOnClickListener {
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        plan1 = findViewById(R.id.plan1)
        plan3 = findViewById(R.id.plan3)
        plan12 = findViewById(R.id.plan12)

        // Выбор тарифа
        plan1.setOnClickListener { selectPlan(1, 199) }
        plan3.setOnClickListener { selectPlan(3, 499) }
        plan12.setOnClickListener { selectPlan(12, 1490) }

        findViewById<View>(R.id.btnBuy).setOnClickListener {
            if (System.currentTimeMillis() - lastClickTime < 1000) return@setOnClickListener
            lastClickTime = System.currentTimeMillis()

            checkLoginAndPay()
        }
    }

    private fun selectPlan(months: Int, priceRub: Int) {
        val bgNormal = R.drawable.bg_glass_card
        val bgSelected = R.drawable.bg_glass_card_selected

        plan1.setBackgroundResource(bgNormal)
        plan3.setBackgroundResource(bgNormal)
        plan12.setBackgroundResource(bgNormal)

        when (months) {
            1 -> plan1.setBackgroundResource(bgSelected)
            3 -> plan3.setBackgroundResource(bgSelected)
            12 -> plan12.setBackgroundResource(bgSelected)
        }

        selectedAmount = priceRub
        selectedPeriod = if (months == 12) "1 Год" else "$months Месяца"
    }

    private fun checkLoginAndPay() {
        // Поддерживаем оплату для Firebase Email, Telegram-пользователей и гостевых устройств
        showPaymentDialog()
    }

    private fun showPaymentDialog() {
        val dialog = BottomSheetDialog(this)
        if (dialog.isShowing) return
        val view = layoutInflater.inflate(R.layout.layout_payment_sheet, null)
        dialog.setContentView(view)

        // Прозрачный фон шторки
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)

        // 1. Оплата картой / ЮKassa
        view.findViewById<View>(R.id.btnPayYoo).setOnClickListener {
            dialog.dismiss()
            createPaymentOnServer("bank_card")
        }

        // 2. Оплата СБП
        view.findViewById<View>(R.id.btnPaySbp)?.setOnClickListener {
            dialog.dismiss()
            createPaymentOnServer("sbp")
        }

        dialog.show()
    }

    private fun createPaymentOnServer(paymentMethod: String) {
        val user = FirebaseAuth.getInstance().currentUser
        val tgKey = SubscriptionManager.getSavedTelegramKey(this)
        val tgUsername = SubscriptionManager.getTelegramUsername(this)
        val deviceId = SubscriptionManager.getDeviceId(this)

        val effectiveUserId = user?.uid
            ?: (if (!tgKey.isNullOrBlank()) tgKey else if (!tgUsername.isNullOrBlank()) "tg_$tgUsername" else "dev_$deviceId")

        val amountString = String.format(java.util.Locale.US, "%.2f", selectedAmount.toDouble())

        ToastHelper.show(this, "Создание счета на оплату...", ToastHelper.Type.INFO)

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val jsonBody = JSONObject().apply {
            put("amount", amountString)
            put("userId", effectiveUserId)
            put("deviceId", deviceId)
            if (!tgUsername.isNullOrBlank()) put("telegramUsername", tgUsername)
            if (!tgKey.isNullOrBlank()) put("telegramKey", tgKey)
            put("method", paymentMethod)
        }

        val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(SERVER_URL)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    ToastHelper.show(this@PremiumActivity, "Ошибка сети: ${e.message}", ToastHelper.Type.ERROR)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseData = response.body?.string()
                if (response.isSuccessful && responseData != null) {
                    try {
                        val json = JSONObject(responseData)
                        val confirmationUrl = json.getString("confirmation_url")

                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(confirmationUrl))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        runOnUiThread {
                            ToastHelper.show(this@PremiumActivity, "Не удалось открыть браузер", ToastHelper.Type.ERROR)
                        }
                    }
                } else {
                    runOnUiThread {
                        ToastHelper.show(this@PremiumActivity, "Ошибка сервера платежей", ToastHelper.Type.ERROR)
                    }
                }
            }
        })
    }
}