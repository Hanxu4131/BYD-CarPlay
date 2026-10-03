package com.shilapi.xcertplay

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

/** Read the car's selected night mode separately from this Activity's resource configuration. */
internal data class HeadUnitAppearance(
    val night: Boolean,
    val manual: Boolean,
    val source: String,
)

internal data class HeadUnitAppearanceSample(
    val selectedMode: Int?,
    val systemUiMode: Int,
    val activityUiMode: Int,
    val selectionLocked: Boolean? = null,
    val currentModeType: Int? = null,
) {
    fun resolve(): HeadUnitAppearance? = when (if (selectionLocked == true ||
        (selectionLocked == null && currentModeType == Configuration.UI_MODE_TYPE_NORMAL)) null else selectedMode) {
        UiModeManager.MODE_NIGHT_YES -> HeadUnitAppearance(true, true, "system_selection")
        UiModeManager.MODE_NIGHT_NO -> HeadUnitAppearance(false, true, "system_selection")
        else -> nightModeOrNull(systemUiMode)?.let { HeadUnitAppearance(it, false, "system_configuration") }
            ?: nightModeOrNull(activityUiMode)?.let { HeadUnitAppearance(it, false, "activity_configuration") }
    }
}

internal class HeadUnitAppearanceReader(context: Context) {
    private val app = context.applicationContext
    private val lockedSelectionMethod = runCatching {
        UiModeManager::class.java.getMethod("isNightModeLocked")
    }.getOrNull()
    fun sample(activityUiMode: Int): HeadUnitAppearanceSample {
        val manager = runCatching { app.getSystemService(UiModeManager::class.java) }.getOrNull()
        return HeadUnitAppearanceSample(
            selectedMode = runCatching { manager?.nightMode }.getOrNull(),
            systemUiMode = app.resources.configuration.uiMode,
            activityUiMode = activityUiMode,
            currentModeType = runCatching { manager?.currentModeType }.getOrNull(),
            // Some car builds lock the framework selection while updating the actual UI configuration.
            // This read-only platform diagnostic is not public on every SDK; unavailable keeps the fallback.
            selectionLocked = runCatching {
                manager?.let { lockedSelectionMethod?.invoke(it) as? Boolean }
            }.getOrNull(),
        )
    }
}
