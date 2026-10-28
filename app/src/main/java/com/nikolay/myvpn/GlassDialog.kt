package com.nikolay.myvpn

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView

object GlassDialog {

    fun show(
        context: Context,
        title: String,
        message: String,
        iconRes: Int = R.drawable.ic_info,
        positiveText: String = "OK",
        negativeText: String? = null, // Опциональный текст для кнопки "Отмена"
        onNegativeClick: () -> Unit = {},
        onPositiveClick: () -> Unit = {}
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.layout_glass_dialog, null)

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(true)
            .create()

        // Установка прозрачного фона окна для корректного отображения скругленных углов
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val ivIcon = view.findViewById<ImageView>(R.id.ivDialogIcon)
        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val tvMessage = view.findViewById<TextView>(R.id.tvDialogMessage)
        val btnPositive = view.findViewById<View>(R.id.btnPositive)
        val btnNegative = view.findViewById<TextView>(R.id.btnNegative)
        val btnPosText = btnPositive as TextView // Кастим для смены текста

        ivIcon.setImageResource(iconRes)
        tvTitle.text = title
        tvMessage.text = message
        btnPosText.text = positiveText

        btnPositive.setOnClickListener {
            dialog.dismiss()
            onPositiveClick()
        }

        // Настройка кнопки отмены
        if (negativeText != null) {
            btnNegative.visibility = View.VISIBLE
            btnNegative.text = negativeText
            btnNegative.setOnClickListener {
                dialog.dismiss()
                onNegativeClick()
            }
        } else {
            btnNegative.visibility = View.GONE
        }

        dialog.show()
    }
}