package com.nikolay.myvpn

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class PremiumActivity : AppCompatActivity() {

    private lateinit var plan1: LinearLayout
    private lateinit var plan3: LinearLayout
    private lateinit var plan12: LinearLayout

    // По умолчанию 1 год
    private var selectedAmount = 1490
    private var selectedPeriod = "1 Year"

    private var lastClickTime: Long = 0

    // !!! ВАЖНО: ВСТАВЬТЕ СЮДА IP ВАШЕГО СЕРВЕРА !!!
    // Например: "http://31.58.87.8:3000/create-payment"
    private val SERVER_URL = "https://unpulverable-hosea-zealous.ngrok-free.dev/create-payment"

    // Ссылка на крипто-оплату
    private val CRYPTO_LINK = "https://t.me/nikolay_support"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_premium)

        findViewById<View>(R.id.btnBack).setOnClickListener {
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        plan1 = findViewById(R.id.plan1)
        plan3 = findViewById(R.id.plan3)
        plan12 = findViewById(R.id.plan12)

        // === ВЫБОР ТАРИФА ===
        plan1.setOnClickListener { selectPlan(1, 199) }   // 199 руб
        plan3.setOnClickListener { selectPlan(3, 499) }   // 499 руб
        plan12.setOnClickListener { selectPlan(12, 1490) } // 1490 руб

        findViewById<View>(R.id.btnBuy).setOnClickListener {
            // Защита от двойного нажатия
            if (System.currentTimeMillis() - lastClickTime < 1000) return@setOnClickListener
            lastClickTime = System.currentTimeMillis()

            checkLoginAndPay()
        }
    }

    private fun selectPlan(months: Int, priceRub: Int) {
        // Сброс фона
        val bgNormal = R.drawable.bg_glass_item
        val bgSelected = R.drawable.bg_glass_selected

        plan1.setBackgroundResource(bgNormal)
        plan3.setBackgroundResource(bgNormal)
        plan12.setBackgroundResource(bgNormal)

        when (months) {
            1 -> plan1.setBackgroundResource(bgSelected)
            3 -> plan3.setBackgroundResource(bgSelected)
            12 -> plan12.setBackgroundResource(bgSelected)
        }

        selectedAmount = priceRub
        selectedPeriod = if (months == 12) "1 Year" else "$months Months"
    }

    private fun checkLoginAndPay() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            ToastHelper.show(this, "Please Log In first", ToastHelper.Type.INFO)
            startActivity(Intent(this, AuthActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            showPaymentDialog()
        }
    }

    private fun showPaymentDialog() {
        val dialog = BottomSheetDialog(this)
        if (dialog.isShowing) return
        val view = layoutInflater.inflate(R.layout.layout_payment_sheet, null)
        dialog.setContentView(view)

        // Прозрачный фон шторки
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // 1. Оплата ЮMoney / Карта
        view.findViewById<View>(R.id.btnPayYoo).setOnClickListener {
            dialog.dismiss()
            // Шлем "bank_card"
            createPaymentOnServer("bank_card")
        }
        // 2. Оплата СБП (Ведет туда же, так как ЮКасса сама дает выбор)
        view.findViewById<View>(R.id.btnPaySbp)?.setOnClickListener {
            dialog.dismiss()
            // Шлем "sbp"
            createPaymentOnServer("sbp")
        }

        // 3. Оплата Криптой
        //view.findViewById<View>(R.id.btnPayCrypto).setOnClickListener {
        //    dialog.dismiss()
        //    payWithCrypto()
        //}
        dialog.show()
    }

    // === НОВЫЙ МЕТОД: ЗАПРОС К СЕРВЕРУ ===
    // Аргумент: "sbp" или "bank_card"
    private fun createPaymentOnServer(paymentMethod: String) {
        val user = FirebaseAuth.getInstance().currentUser ?: return

        // Форматируем цену (199.00)
        val amountString = String.format(java.util.Locale.US, "%.2f", selectedAmount.toDouble())

        Toast.makeText(this, "Creating payment...", Toast.LENGTH_SHORT).show()

        val client = OkHttpClient()

        val jsonBody = JSONObject()
        jsonBody.put("amount", amountString)
        jsonBody.put("userId", user.uid)

        // === ОТПРАВЛЯЕМ МЕТОД ОПЛАТЫ ===
        jsonBody.put("method", paymentMethod)
        // ================================

        val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(SERVER_URL)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    ToastHelper.show(this@PremiumActivity, "Error: ${e.message}", ToastHelper.Type.ERROR)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseData = response.body?.string()
                if (response.isSuccessful && responseData != null) {
                    try {
                        val json = JSONObject(responseData)
                        val confirmationUrl = json.getString("confirmation_url")

                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(confirmationUrl))
                        startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    runOnUiThread {
                        ToastHelper.show(this@PremiumActivity, "Server Error", ToastHelper.Type.ERROR)
                    }
                }
            }
        })
    }

    //private fun payWithCrypto() {
    //    try {
    //        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(CRYPTO_LINK))
    //        startActivity(intent)
    //    } catch (e: Exception) {
    //        ToastHelper.show(this, "Browser not found", ToastHelper.Type.ERROR)
    //    }
    //}
}