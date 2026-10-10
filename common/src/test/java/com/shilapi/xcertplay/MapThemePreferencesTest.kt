package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MapThemePreferencesTest {
    @Test
    fun defaultsOnAndPersistsIndependentlyOfDisplaySettings() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("xcertplay_airplay", 0)
        prefs.edit().remove("map_follow_carplay_ui").putInt("display_scale_tenths", 7).commit()
        assertTrue(AirPlayPersistence.loadMapFollowUi(context))
        AirPlayPersistence.saveMapFollowUi(context, false)
        assertFalse(AirPlayPersistence.loadMapFollowUi(context.applicationContext))
        assertFalse(prefs.getBoolean("map_follow_carplay_ui", true))
        assertEquals(7, prefs.getInt("display_scale_tenths", 0))
        AirPlayPersistence.saveMapFollowUi(context, true)
        assertTrue(AirPlayPersistence.loadMapFollowUi(context.applicationContext))
        assertEquals(7, prefs.getInt("display_scale_tenths", 0))
    }
}
