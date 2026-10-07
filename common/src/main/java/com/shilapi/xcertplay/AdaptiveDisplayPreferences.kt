package com.shilapi.xcertplay

import android.content.Context

object AdaptiveDisplayPreferences {
    private const val FILE = "adaptive_h264_display"
    fun enabled(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply() }
    fun squareCorners(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("square_corners", true)
    fun setSquareCorners(context: Context, enabled: Boolean) { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("square_corners", enabled).apply() }

    private fun areaKey(canvas: AdaptiveViewAreaHistory.Size) = "split_areas_${canvas.width}x${canvas.height}"

    internal fun splitAreas(context: Context, canvas: AdaptiveViewAreaHistory.Size): List<AdaptiveViewAreaHistory.Size> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(areaKey(canvas), "").orEmpty()
            .split(';').mapNotNull { entry ->
                val parts = entry.split('x')
                val width = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val height = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                AdaptiveViewAreaHistory.Size(width, height).takeIf { AdaptiveViewAreaHistory.split(it, canvas) }
            }.take(AdaptiveViewAreaHistory.MAX_SPLIT_AREAS)

    internal fun rememberSplitArea(context: Context, canvas: AdaptiveViewAreaHistory.Size, size: AdaptiveViewAreaHistory.Size) {
        val old = splitAreas(context, canvas)
        val updated = AdaptiveViewAreaHistory.remember(old, size, canvas)
        if (updated != old) context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(areaKey(canvas), updated.joinToString(";") { "${it.width}x${it.height}" }).apply()
    }
}
