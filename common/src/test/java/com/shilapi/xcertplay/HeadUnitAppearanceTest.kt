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
        assertEquals(HeadUnitAppearance(true, false, "system_configuration"),
            HeadUnitAppearanceSample(UiModeManager.MODE_NIGHT_NO, night, day, selectionLocked = true).resolve())
        assertEquals(HeadUnitAppearance(false, false, "system_configuration"),
            HeadUnitAppearanceSample(UiModeManager.MODE_NIGHT_YES, day, night, selectionLocked = true).resolve())
        assertNull(HeadUnitAppearanceSample(UiModeManager.MODE_NIGHT_NO, 0, 0, selectionLocked = true).resolve())
    }
    @Test fun automaticModeUsesSystemConfigurationBeforeTheActivityCopy() {
        assertEquals(HeadUnitAppearance(true, false, "system_configuration"),
            HeadUnitAppearanceSample(UiModeManager.MODE_NIGHT_AUTO, night, day).resolve())
        assertEquals(HeadUnitAppearance(false, false, "system_configuration"),
            HeadUnitAppearanceSample(UiModeManager.MODE_NIGHT_AUTO, day, night).resolve())
    }
    @Test fun unavailableManagerFallsBackWithoutTreatingUndefinedAsDay() {
        assertEquals(true, HeadUnitAppearanceSample(null, night, day).resolve()?.night)
        assertEquals(true, HeadUnitAppearanceSample(null, 0, night).resolve()?.night)
        assertNull(HeadUnitAppearanceSample(null, 0, 0).resolve())
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
