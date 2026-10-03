package com.shilapi.xcertplay

import kotlin.math.roundToInt

/** The iPhone's navigation safe rectangle, independent of the projection window placement. */
internal object LegacyClusterKeyArea {
    // Original CarPlayClusterDisplay insets: left 35, right 36, top 16, bottom 25 percent.
    data class Settings(val horizontal: Double = 49.5, val vertical: Double = 45.5, val scalePercent: Int = 100)
    data class Rect(val left: Double, val top: Double, val right: Double, val bottom: Double)
    data class Insets(val top: Int, val bottom: Int, val left: Int, val right: Int)
    private const val WIDTH = 29.0
    private const val HEIGHT = 59.0

    fun sanitize(value: Settings): Settings {
        val scale = value.scalePercent.coerceIn(20, 165) / 5 * 5
        val halfWidth = WIDTH * scale / 200
        val halfHeight = HEIGHT * scale / 200
        val horizontal = value.horizontal.takeIf { it.isFinite() } ?: 49.5
        val vertical = value.vertical.takeIf { it.isFinite() } ?: 45.5
        return Settings(horizontal.coerceIn(halfWidth, 100 - halfWidth),
            vertical.coerceIn(halfHeight, 100 - halfHeight), scale)
    }

    fun move(value: Settings, horizontal: Int, vertical: Int, size: Int): Settings = sanitize(
        value.copy(horizontal = value.horizontal + horizontal / 10.0, vertical = value.vertical + vertical / 10.0,
            scalePercent = value.scalePercent + size))

    fun rect(value: Settings = Settings()): Rect {
        val settings = sanitize(value)
        val halfWidth = WIDTH * settings.scalePercent / 200
        val halfHeight = HEIGHT * settings.scalePercent / 200
        return Rect(settings.horizontal - halfWidth, settings.vertical - halfHeight,
            settings.horizontal + halfWidth, settings.vertical + halfHeight)
    }

    /** Translate the phone's reference rectangle onto the current map SurfaceView. */
    fun project(map: LegacyClusterLayout.Plan, value: Settings): LegacyClusterLayout.Plan {
        val insets = insets(map.width, map.height, value)
        return LegacyClusterLayout.Plan(map.left + insets.left, map.top + insets.top,
            map.width - insets.left - insets.right, map.height - insets.top - insets.bottom)
    }

    fun insets(width: Int, height: Int, value: Settings = Settings()): Insets {
        require(width > 0 && height > 0)
        val rect = rect(value)
        val left = (width * rect.left / 100).roundToInt().coerceIn(0, width - 1)
        val top = (height * rect.top / 100).roundToInt().coerceIn(0, height - 1)
        val right = (width * rect.right / 100).roundToInt().coerceIn(left + 1, width)
        val bottom = (height * rect.bottom / 100).roundToInt().coerceIn(top + 1, height)
        return Insets(top, height - bottom, left, width - right)
    }
}
