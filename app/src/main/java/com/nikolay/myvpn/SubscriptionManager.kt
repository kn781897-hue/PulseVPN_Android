package com.nikolay.myvpn

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

object SubscriptionManager {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // Проверка статуса 
    fun checkSubscriptionStatus(onComplete: (isPremium: Boolean) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            onComplete(false)
            return
        }

        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val expiryDate = document.getLong("subscriptionExpiry") ?: 0
                    val currentTime = System.currentTimeMillis()

                    // Если время истекло
                    if (currentTime > expiryDate) {
                        // Отключаем премиум в базе
                        setPremiumStatus(false, 0)
                        onComplete(false)
                    } else {
                        // Подписка активна
                        onComplete(true)
                    }
                } else {
                    onComplete(false)
                }
            }
            .addOnFailureListener {
                onComplete(false)
            }
    }

    // Активация премиума
    fun activatePremium(months: Int, onSuccess: () -> Unit) {
        val user = auth.currentUser ?: return

        // Вычисляем новую дату
        val millisInMonth = 2592000000L // 30 дней
        val newExpiryDate = System.currentTimeMillis() + (months * millisInMonth)

        val data = hashMapOf(
            "isPremium" to true,
            "subscriptionExpiry" to newExpiryDate,
            "email" to user.email
        )

        // Сохраняем в базу 
        db.collection("users").document(user.uid)
            .set(data, SetOptions.merge())
            .addOnSuccessListener {
                onSuccess()
            }
    }

    // Внутренний метод для смены статуса
    private fun setPremiumStatus(isActive: Boolean, expiry: Long) {
        val user = auth.currentUser ?: return
        val data = hashMapOf(
            "isPremium" to isActive,
            "subscriptionExpiry" to expiry
        )
        db.collection("users").document(user.uid).set(data, SetOptions.merge())
    }
}
