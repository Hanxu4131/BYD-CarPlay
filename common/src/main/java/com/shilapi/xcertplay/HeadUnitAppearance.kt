package com.shilapi.xcertplay

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

/** Read the car's selected night mode separately from this Activity's resource configuration. */
internal data class HeadUnitAppearance(
    val night: Boolean,
    // Receiver policy: use this car-provided appearance, regardless of how Android chose it.
    // This is not UiModeManager's selection/lock state.
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
        else -> nightModeOrNull(systemUiMode)?.let { HeadUnitAppearance(it, true, "system_configuration") }
            ?: nightModeOrNull(activityUiMode)?.let { HeadUnitAppearance(it, true, "activity_configuration") }
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

/** A successful socket write is not an acknowledgement that the phone applied the appearance. */
internal class HeadUnitAppearanceRefresh(private val retryDelayMillis: Long) {
    private var retryAtMillis: Long? = null

    // Resume, focus and surface callbacks can describe the same transition; coalesce them.
    fun request(nowMillis: Long): Boolean {
        if (retryAtMillis != null) return false
        retryAtMillis = nowMillis + retryDelayMillis
        return true
    }

    fun isDue(nowMillis: Long): Boolean = retryAtMillis?.let { nowMillis >= it } ?: false

    // Use the send request time; a slow initial write must not consume the delayed retry.
    fun sent(sendRequestedAtMillis: Long) {
        if (isDue(sendRequestedAtMillis)) retryAtMillis = null
    }

    fun clear() {
        retryAtMillis = null
    }
}
