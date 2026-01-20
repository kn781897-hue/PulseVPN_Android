package com.nikolay.myvpn


import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

object ToastHelper {

    // Типы уведомлений
    enum class Type { SUCCESS, ERROR, INFO }

    fun show(context: Context, message: String, type: Type) {
        val inflater = LayoutInflater.from(context)
        val layout = inflater.inflate(R.layout.layout_toast, null)

        // Находим элементы
        val tvMessage = layout.findViewById<TextView>(R.id.tvToastMessage)
        val ivIcon = layout.findViewById<ImageView>(R.id.ivToastIcon)

        // Устанавливаем текст
        tvMessage.text = message

        // Настраиваем цвета и иконки
        when (type) {
            Type.SUCCESS -> {
                // Зеленый
                ivIcon.setImageResource(R.drawable.ic_check_circle)
                ivIcon.setColorFilter(Color.parseColor("#4CAF50"))
            }
            Type.ERROR -> {
                // Красный (нужна иконка ошибки, или используем info)
                ivIcon.setImageResource(android.R.drawable.stat_notify_error)
                ivIcon.setColorFilter(Color.parseColor("#F44336"))
            }
            Type.INFO -> {
                // Синий/Белый
                ivIcon.setImageResource(R.drawable.ic_info)
                ivIcon.setColorFilter(Color.parseColor("#00E5FF"))
            }
        }

        // Создаем и показываем Тост
        val toast = Toast(context)
        toast.duration = Toast.LENGTH_SHORT
        toast.view = layout

        // Позиция: Снизу, немного отступив от края (как в iOS)
        toast.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 150)

        toast.show()
    }
}