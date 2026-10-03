package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StartupLogoPreferencesTest {
    @Test fun defaultPreservesUltraAndBothDisplayReadersUseThePersistedChoice() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("carplay_startup_artwork", 0)
        val existed = prefs.contains("ultra")
        val original = prefs.getBoolean("ultra", true)
        try {
            prefs.edit().clear().commit()
            assertTrue(StartupLogoPreferences.ultra(context))
            StartupLogoPreferences.setUltra(context, false)
            assertFalse(StartupLogoPreferences.ultra(context))
            assertFalse(StartupLogoPreferences.ultra(context.applicationContext))
            // The reader has no mode cache; a fresh preferences lookup observes the stored value.
            assertFalse(context.getSharedPreferences("carplay_startup_artwork", 0).getBoolean("ultra", true))
            StartupLogoPreferences.setUltra(context, true)
            assertTrue(StartupLogoPreferences.ultra(context.applicationContext))
        } finally {
            prefs.edit().clear().also { if (existed) it.putBoolean("ultra", original) }.commit()
        }
    }
}
