package com.nikolay.myvpn

import android.view.View

object GlassHelper {


    fun applyGlassEffect(view: View?) {
        view?.let {
            it.clipToOutline = true
        }
    }
}
