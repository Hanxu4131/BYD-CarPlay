package com.shilapi.xcertplay

import android.content.Context

/** Display artwork only; it does not select or negotiate the iPhone's CarPlay mode. */
internal object StartupLogoPreferences {
    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences("carplay_startup_artwork", Context.MODE_PRIVATE)
    fun ultra(context: Context): Boolean = preferences(context).getBoolean("ultra", true)
    fun setUltra(context: Context, enabled: Boolean) { preferences(context).edit().putBoolean("ultra", enabled).apply() }
}
