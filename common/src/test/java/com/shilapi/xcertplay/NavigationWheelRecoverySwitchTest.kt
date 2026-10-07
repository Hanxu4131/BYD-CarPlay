package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NavigationWheelRecoverySwitchTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val legacy get() = context.getSharedPreferences("navigation_wheel_service",Context.MODE_PRIVATE)
    @Before fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay",Context.MODE_PRIVATE).edit().clear().commit()
        legacy.edit().clear().commit()
    }
    @Test fun masterOnIgnoresOldDisabledAndAutoRepairPreferences() {
        legacy.edit().putBoolean("enabled",false).putBoolean("auto_repair",false).commit()
        AirPlayPersistence.saveNavigationVolumeWheelEnabled(context,true)
        assertTrue(NavigationWheelServiceRecovery.enabled(context))
        assertTrue(NavigationWheelServiceRecovery.autoRepair(context))
        // Old records are neither needed nor rewritten by reading the unified switch.
        assertFalse(legacy.getBoolean("enabled",true))
        assertFalse(legacy.getBoolean("auto_repair",true))
    }
    @Test fun masterOffWinsOverLegacyEnabledAndSystemRegistrationIsPreserved() {
        legacy.edit().putBoolean("enabled",true).putBoolean("auto_repair",true).commit()
        val registered="other.pkg/.Service:com.shihab.diplay.tang21test/com.shilapi.xcertplay.NavigationWheelAccessibilityService"
        android.provider.Settings.Secure.putString(context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,registered)
        AirPlayPersistence.saveNavigationVolumeWheelEnabled(context,false)
        assertFalse(NavigationWheelServiceRecovery.enabled(context))
        assertFalse(NavigationWheelServiceRecovery.autoRepair(context))
        assertEquals(registered,android.provider.Settings.Secure.getString(context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }
}
