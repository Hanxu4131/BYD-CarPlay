package com.shilapi.xcertplay

import android.content.Context

object AdaptiveDisplayPreferences {
    private const val FILE = "adaptive_h264_display"
    fun enabled(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply() }
    fun squareCorners(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("square_corners", true)
    fun setSquareCorners(context: Context, enabled: Boolean) { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("square_corners", enabled).apply() }
}
