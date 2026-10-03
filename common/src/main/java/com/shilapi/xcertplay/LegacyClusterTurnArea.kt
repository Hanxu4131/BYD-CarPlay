package com.shilapi.xcertplay

import kotlin.math.roundToInt

/** Independent local turn-card coordinates, relative to the projected map. */
internal object LegacyClusterTurnArea {
    private const val WIDTH = 29.0
    private const val HEIGHT = 27.84 // Existing card: width * .36 on the 1920:720 map.
    data class Settings(val horizontal: Double = 49.5, val vertical: Double = 29.92, val scalePercent: Int = 100)

    fun sanitize(value: Settings): Settings {
        val scale = value.scalePercent.coerceIn(20, 165) / 5 * 5
        val halfWidth = WIDTH * scale / 200
        val halfHeight = HEIGHT * scale / 200
        return Settings((value.horizontal.takeIf { it.isFinite() } ?: 49.5).coerceIn(halfWidth, 100 - halfWidth),
            (value.vertical.takeIf { it.isFinite() } ?: 29.92).coerceIn(halfHeight, 100 - halfHeight), scale)
    }

    /** Match the former key-area-bound card once; later key-area changes are independent. */
    fun fromKeyArea(key: LegacyClusterKeyArea.Settings): Settings {
        val safe = LegacyClusterKeyArea.sanitize(key)
        val rect = LegacyClusterKeyArea.rect(safe)
        return sanitize(Settings(safe.horizontal, rect.top + HEIGHT * safe.scalePercent / 200, safe.scalePercent))
    }

    fun move(value: Settings, horizontal: Int, vertical: Int, size: Int): Settings = sanitize(
        value.copy(horizontal = value.horizontal + horizontal / 10.0, vertical = value.vertical + vertical / 10.0,
            scalePercent = value.scalePercent + size))

    fun project(map: LegacyClusterLayout.Plan, value: Settings): LegacyClusterLayout.Plan {
        val safe = sanitize(value)
        val width = (map.width * WIDTH * safe.scalePercent / 10_000).roundToInt().coerceIn(1, map.width)
        val height = (map.height * HEIGHT * safe.scalePercent / 10_000).roundToInt().coerceIn(1, map.height)
        val left = (map.width * safe.horizontal / 100 - width / 2.0).roundToInt().coerceIn(0, map.width - width)
        val top = (map.height * safe.vertical / 100 - height / 2.0).roundToInt().coerceIn(0, map.height - height)
        return LegacyClusterLayout.Plan(map.left + left, map.top + top, width, height)
    }
}
