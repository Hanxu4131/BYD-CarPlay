package com.shilapi.xcertplay.media

import android.content.Context

object AmbientMusicSettings {
    private const val PREFS = "ambient_music"
    data class Values(val enabled: Boolean = false, val music: Boolean = true,
        val colorMode: AmbientColorMode = AmbientColorMode.BEAT,
        val speed: AmbientColorSpeed = AmbientColorSpeed.STANDARD, val selectedColors: List<Int> = listOf(1), val colorCycle: Boolean = false, val color: Int = 1, val brightness: Int = 0, val area: Int = 3,
        val colorSource: AmbientColorSource = AmbientColorSource.SELECTED)
    fun load(context: Context): Values {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Values(p.getBoolean("enabled", false), p.getBoolean("music", true),
            runCatching { AmbientColorMode.valueOf(p.getString("colorMode", "BEAT") ?: "BEAT") }.getOrDefault(AmbientColorMode.BEAT),
            runCatching { AmbientColorSpeed.valueOf(p.getString("speed", "STANDARD") ?: "STANDARD") }.getOrDefault(AmbientColorSpeed.STANDARD),
            normalizeAmbientPalette(p.getStringSet("selectedColors", null)?.mapNotNull { it.toIntOrNull() }
                ?: listOf(p.getInt("color", 1)), p.getInt("color", 1)),
            p.getBoolean("colorCycle", false), p.getInt("color", 1).coerceIn(1, 31), p.getInt("brightness", 0).coerceIn(0, 6),
            p.getInt("area", 3).coerceIn(1, 3),
            runCatching { AmbientColorSource.valueOf(p.getString("colorSource", "SELECTED") ?: "SELECTED") }.getOrDefault(AmbientColorSource.SELECTED))
    }
    fun save(context: Context, values: Values) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", values.enabled).putBoolean("music", values.music)
            .putBoolean("colorCycle", values.colorCycle).putString("colorMode", values.colorMode.name)
            .putString("speed", values.speed.name).putString("colorSource", values.colorSource.name)
            .putStringSet("selectedColors", normalizeAmbientPalette(values.selectedColors, values.color).map { it.toString() }.toSet())
            .putInt("color", values.color.coerceIn(1, 31))
            .putInt("brightness", values.brightness.coerceIn(0, 6))
            .putInt("area", values.area.coerceIn(1, 3)).apply()
        AmbientMusicController.settingsChanged(context)
    }
}
