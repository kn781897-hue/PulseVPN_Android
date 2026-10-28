package com.nikolay.myvpn

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class AuthActivity : AppCompatActivity() {

    private var isLoginMode = true
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

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

        setContentView(R.layout.activity_auth)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // Telegram Key Section
        val etTelegramKey = findViewById<EditText>(R.id.etTelegramKey)
        val btnActivateKey = findViewById<View>(R.id.btnActivateKey)
        val btnOpenTelegramBot = findViewById<View>(R.id.btnOpenTelegramBot)

        val savedKey = SubscriptionManager.getSavedTelegramKey(this)
        if (!savedKey.isNullOrEmpty()) {
            etTelegramKey.setText(savedKey)
        }

        btnActivateKey.setOnClickListener {
            val key = etTelegramKey.text.toString().trim()
            if (key.isEmpty()) {
                ToastHelper.show(this, "Введите ключ из Telegram", ToastHelper.Type.ERROR)
                return@setOnClickListener
            }

            btnActivateKey.isEnabled = false
            ToastHelper.show(this, "Проверка ключа...", ToastHelper.Type.INFO)

            SubscriptionManager.setLoggedOut(this, false)
            SubscriptionManager.activateTelegramKey(this, key) { success, message ->
                btnActivateKey.isEnabled = true
                if (success) {
                    ToastHelper.show(this, message, ToastHelper.Type.SUCCESS)
                    finish()
                } else {
                    ToastHelper.show(this, message, ToastHelper.Type.ERROR)
                }
            }
        }

        btnOpenTelegramBot.setOnClickListener {
            SubscriptionManager.setLoggedOut(this, false)
            SubscriptionManager.openTelegramLink(this)
        }

        // Email / Password Section
        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        val btnAction = findViewById<TextView>(R.id.btnAction)
        val tvSwitchMode = findViewById<TextView>(R.id.tvSwitchMode)
        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val tvForgotPassword = findViewById<TextView>(R.id.tvForgotPassword)

        // Восстановление пароля
        tvForgotPassword.setOnClickListener {
            showResetDialog(etEmail.text.toString())
        }

        // Переключение режима
        tvSwitchMode.setOnClickListener {
            isLoginMode = !isLoginMode
            if (isLoginMode) {
                tvTitle.text = "Вход в аккаунт"
                btnAction.text = "ВОЙТИ"
                tvSwitchMode.text = "Нет аккаунта? Зарегистрироваться"
                tvForgotPassword.visibility = View.VISIBLE
            } else {
                tvTitle.text = "Создать аккаунт"
                btnAction.text = "ЗАРЕГИСТРИРОВАТЬСЯ"
                tvSwitchMode.text = "Уже есть аккаунт? Войти"
                tvForgotPassword.visibility = View.GONE
            }
        }

        btnAction.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val pass = etPassword.text.toString().trim()

            if (email.isEmpty() || pass.isEmpty()) {
                ToastHelper.show(this, "Заполните все поля", ToastHelper.Type.ERROR)
                return@setOnClickListener
            }

            if (isLoginMode) {
                auth.signInWithEmailAndPassword(email, pass)
                    .addOnSuccessListener {
                        SubscriptionManager.setLoggedOut(this, false)
                        ToastHelper.show(this, "Успешный вход!", ToastHelper.Type.SUCCESS)
                        finish()
                    }
                    .addOnFailureListener { e ->
                        ToastHelper.show(this, "Ошибка: ${e.message}", ToastHelper.Type.ERROR)
                    }
            } else {
                auth.createUserWithEmailAndPassword(email, pass)
                    .addOnSuccessListener { result ->
                        SubscriptionManager.setLoggedOut(this, false)
                        val userMap = hashMapOf(
                            "email" to email,
                            "isPremium" to false,
                            "subscriptionExpiry" to 0L,
                            "deviceId" to SubscriptionManager.getDeviceId(this)
                        )
                        db.collection("users").document(result.user!!.uid).set(userMap)
                            .addOnSuccessListener {
                                ToastHelper.show(this, "Аккаунт создан!", ToastHelper.Type.SUCCESS)
                                finish()
                            }
                    }
                    .addOnFailureListener { e ->
                        ToastHelper.show(this, "Ошибка: ${e.message}", ToastHelper.Type.ERROR)
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed) return
        // Проверяем автоматическую привязку при возвращении из Telegram
        SubscriptionManager.syncWithFirestore(this) { synced ->
            if (synced && !isFinishing && !isDestroyed) {
                runOnUiThread {
                    ToastHelper.show(this, "Telegram успешно привязан!", ToastHelper.Type.SUCCESS)
                    finish()
                }
            }
        }
    }

    private fun showResetDialog(prefilledEmail: String) {
        val dialogView = layoutInflater.inflate(R.layout.layout_dialog_reset, null)
        val etResetEmail = dialogView.findViewById<EditText>(R.id.etResetEmail)
        val btnSend = dialogView.findViewById<View>(R.id.btnSendReset)

        if (prefilledEmail.isNotEmpty()) {
            etResetEmail.setText(prefilledEmail)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnSend.setOnClickListener {
            val email = etResetEmail.text.toString().trim()
            if (email.isEmpty()) {
                ToastHelper.show(this, "Enter email", ToastHelper.Type.INFO)
                return@setOnClickListener
            }

            auth.sendPasswordResetEmail(email)
                .addOnSuccessListener {
                    ToastHelper.show(this, "Reset link sent to your email!", ToastHelper.Type.INFO)
                    dialog.dismiss()
                }
                .addOnFailureListener { e ->
                    ToastHelper.show(this, "Error: ${e.message}", ToastHelper.Type.ERROR)
                }
        }

        dialog.show()
    }
}