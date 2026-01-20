package com.nikolay.myvpn

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class AuthActivity : AppCompatActivity() {

    private var isLoginMode = true
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }

        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        val btnAction = findViewById<TextView>(R.id.btnAction)
        val tvSwitchMode = findViewById<TextView>(R.id.tvSwitchMode)
        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val tvForgotPassword = findViewById<TextView>(R.id.tvForgotPassword) // Наша новая кнопка

        // ЛОГИКА ВОССТАНОВЛЕНИЯ ПАРОЛЯ
        tvForgotPassword.setOnClickListener {
            // Если email уже введен в поле, используем его, иначе пустая строка
            showResetDialog(etEmail.text.toString())
        }

        // Переключение режима
        tvSwitchMode.setOnClickListener {
            isLoginMode = !isLoginMode
            if (isLoginMode) {
                tvTitle.text = "Welcome Back"
                btnAction.text = "LOG IN"
                tvSwitchMode.text = "Don't have an account? Sign Up"
                tvForgotPassword.visibility = View.VISIBLE // Показываем кнопку
            } else {
                tvTitle.text = "Create Account"
                btnAction.text = "SIGN UP"
                tvSwitchMode.text = "Already have an account? Log In"
                tvForgotPassword.visibility = View.GONE // Скрываем кнопку
            }
        }

        btnAction.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val pass = etPassword.text.toString().trim()

            if (email.isEmpty() || pass.isEmpty()) {
                ToastHelper.show(this, "Please fill all fields", ToastHelper.Type.ERROR)
                return@setOnClickListener
            }

            if (isLoginMode) {
                // ЛОГИН
                auth.signInWithEmailAndPassword(email, pass)
                    .addOnSuccessListener {
                        ToastHelper.show(this, "Login Successful!", ToastHelper.Type.SUCCESS)
                        finish()
                    }
                    .addOnFailureListener { e ->
                        ToastHelper.show(this, "Error: ${e.message}", ToastHelper.Type.ERROR)
                    }
            } else {
                // РЕГИСТРАЦИЯ
                auth.createUserWithEmailAndPassword(email, pass)
                    .addOnSuccessListener { result ->
                        val userMap = hashMapOf(
                            "email" to email,
                            "isPremium" to false,
                            "subscriptionExpiry" to 0L
                        )
                        db.collection("users").document(result.user!!.uid).set(userMap)
                            .addOnSuccessListener {
                                ToastHelper.show(this, "Account Created!", ToastHelper.Type.SUCCESS)
                                finish()
                            }
                    }
                    .addOnFailureListener { e ->
                        ToastHelper.show(this, "Error: ${e.message}", ToastHelper.Type.ERROR)
                    }
            }
        }
    }

    // ФУНКЦИЯ ПОКАЗА ДИАЛОГА
    private fun showResetDialog(prefilledEmail: String) {
        val dialogView = layoutInflater.inflate(R.layout.layout_dialog_reset, null)
        val etResetEmail = dialogView.findViewById<EditText>(R.id.etResetEmail)
        val btnSend = dialogView.findViewById<android.view.View>(R.id.btnSendReset)

        if (prefilledEmail.isNotEmpty()) {
            etResetEmail.setText(prefilledEmail)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        // Делаем фон диалога прозрачным, чтобы видеть наше стекло
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnSend.setOnClickListener {
            val email = etResetEmail.text.toString().trim()
            if (email.isEmpty()) {
                ToastHelper.show(this, "Enter email", ToastHelper.Type.INFO)
                return@setOnClickListener
            }

            // === ОТПРАВКА ПИСЬМА ЧЕРЕЗ FIREBASE ===
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