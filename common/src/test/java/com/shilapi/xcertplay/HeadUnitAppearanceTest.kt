package com.shilapi.xcertplay

import android.app.UiModeManager
import android.content.res.Configuration
import org.junit.Assert.*
import org.junit.Test

class HeadUnitAppearanceTest {
    private val day = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_CAR
    private val night = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_CAR
    @Test fun readsSystemSelectionEvenWhenActivityResourcesAreStale() {
        assertEquals(HeadUnitAppearance(true, true, "system_selection"),
            HeadUnitAppearanceSample(UiModeManager.MODE_NIGHT_YES, day, day).resolve())
        assertEquals(HeadUnitAppearance(false, true, "system_selection"),
            HeadUnitAppearanceSample(UiModeManager.MODE_NIGHT_NO, night, night).resolve())
    }
    @Test fun lockedFrameworkSelectionDoesNotOverrideTheCarsActualConfiguration() {
        assertEquals(HeadUnitAppearance(true, true, "system_configuration"),
            HeadUnitAppearanceSample(UiModeManager.MODE_NIGHT_NO, night, day, selectionLocked = true).resolve())
        assertEquals(HeadUnitAppearance(false, true, "system_configuration"),
            HeadUnitAppearanceSample(UiModeManager.MODE_NIGHT_YES, day, night, selectionLocked = true).resolve())
        assertNull(HeadUnitAppearanceSample(UiModeManager.MODE_NIGHT_NO, 0, 0, selectionLocked = true).resolve())
    }
    @Test fun automaticModeUsesSystemConfigurationBeforeTheActivityCopy() {
        assertEquals(HeadUnitAppearance(true, true, "system_configuration"),
            HeadUnitAppearanceSample(UiModeManager.MODE_NIGHT_AUTO, night, day).resolve())
        assertEquals(HeadUnitAppearance(false, true, "system_configuration"),
            HeadUnitAppearanceSample(UiModeManager.MODE_NIGHT_AUTO, day, night).resolve())
    }
    @Test fun unavailableManagerStillMakesReliableConfigurationAuthoritative() {
        assertEquals(HeadUnitAppearance(true, true, "system_configuration"),
            HeadUnitAppearanceSample(null, night, day).resolve())
        assertEquals(HeadUnitAppearance(false, true, "system_configuration"),
            HeadUnitAppearanceSample(null, day, night).resolve())
        assertEquals(HeadUnitAppearance(true, true, "activity_configuration"),
            HeadUnitAppearanceSample(null, 0, night).resolve())
        assertEquals(HeadUnitAppearance(false, true, "activity_configuration"),
            HeadUnitAppearanceSample(null, 0, day).resolve())
        assertNull(HeadUnitAppearanceSample(null, 0, 0).resolve())
    }
    @Test fun lockedNormalModeUsesLiveSystemConfigurationWithReceiverOverride() {
        val normalDay = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL
        val normalNight = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL
        // The recorded BYD transition changed systemUiMode before the Activity copy caught up.
        assertEquals(HeadUnitAppearance(true, true, "system_configuration"),
            HeadUnitAppearanceSample(UiModeManager.MODE_NIGHT_NO, normalNight, normalDay,
                selectionLocked = true, currentModeType = Configuration.UI_MODE_TYPE_NORMAL).resolve())
        assertEquals(HeadUnitAppearance(false, true, "system_configuration"),
            HeadUnitAppearanceSample(UiModeManager.MODE_NIGHT_YES, normalDay, normalNight,
                selectionLocked = true, currentModeType = Configuration.UI_MODE_TYPE_NORMAL).resolve())
        assertNull(HeadUnitAppearanceSample(UiModeManager.MODE_NIGHT_YES, 0, 0,
            selectionLocked = true, currentModeType = Configuration.UI_MODE_TYPE_NORMAL).resolve())
    }
    @Test fun unknownOrCustomModesUseActualAppearanceInsteadOfGuessingDark() {
        assertEquals(false, HeadUnitAppearanceSample(3, day, night).resolve()?.night)
        assertEquals(true, HeadUnitAppearanceSample(99, night, day).resolve()?.night)
    }
    @Test fun ordinaryModeWithoutTheHiddenLockGetterFollowsActualNightConfiguration() {
        assertEquals(true, HeadUnitAppearanceSample(UiModeManager.MODE_NIGHT_NO, night, day,
            selectionLocked = null, currentModeType = Configuration.UI_MODE_TYPE_NORMAL).resolve()?.night)
        assertEquals(false, HeadUnitAppearanceSample(UiModeManager.MODE_NIGHT_YES, day, night,
            selectionLocked = null, currentModeType = Configuration.UI_MODE_TYPE_NORMAL).resolve()?.night)
        assertEquals(true, HeadUnitAppearanceSample(UiModeManager.MODE_NIGHT_YES, day, day,
            selectionLocked = null, currentModeType = Configuration.UI_MODE_TYPE_CAR).resolve()?.night)
    }
}
