package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.hud.BydOutputSettings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class IndependentHudSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("diplay_byd_outputs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test fun legacyNavigationDoesNotImplicitlyEnableIndependentHud() {
        BydOutputSettings.setEnabled(context, true)
        assertFalse(BydOutputSettings.hudEnabled(context))
        assertFalse(BydOutputSettings.independentHudAvailable(context))
    }

    @Test fun hudSwitchLeavesExistingInstrumentAndNavigationChoicesAlone() {
        for (navigation in listOf(false, true)) {
            BydOutputSettings.setEnabled(context, navigation)
            AirPlayPersistence.saveClusterMapEnabled(context, true)
            for (hud in listOf(true, false)) {
                BydOutputSettings.setHudEnabled(context, hud)
                assertEquals(hud, BydOutputSettings.hudEnabled(context))
                assertEquals(navigation, BydOutputSettings.enabled(context))
                assertTrue(AirPlayPersistence.loadClusterMapEnabled(context))
            }
        }
    }
}
