package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceCommandsTest {
    @Test
    fun buildsActualThemeAndManualSettingIndependently() {
        for (night in listOf(false, true)) {
            for (manual in listOf(false, true)) {
                val commands = AppearanceCommands.build(CarPlayAppearance(night, manual), false)
                assertEquals(listOf("setNightMode", "uiAppearanceUpdate", "mapAppearanceUpdate"), commands.map { it["type"] })
                assertEquals(mapOf("nightMode" to night), commands.first()["params"])
                commands.drop(1).forEach {
                    assertEquals(
                        mapOf(
                            "uuid" to AirPlayInfoPlist.MAIN_UUID,
                            "appearanceMode" to if (night) 1 else 0,
                            "appearanceSetting" to if (manual) 2 else 0,
                        ),
                        it["params"],
                    )
                }
            }
        }
    }

    @Test
    fun updatesOnlyAdvertisedDisplays() {
        val appearance = CarPlayAppearance(true, false)
        val mainOnly = AppearanceCommands.build(appearance, false)
        val withCluster = AppearanceCommands.build(appearance, true)
        assertEquals(3, mainOnly.size)
        assertEquals(5, withCluster.size)
        assertEquals(
            listOf(AirPlayInfoPlist.MAIN_UUID, AirPlayInfoPlist.MAIN_UUID, AirPlayInfoPlist.ALT_UUID, AirPlayInfoPlist.ALT_UUID),
            withCluster.drop(1).map { (it["params"] as Map<*, *>)["uuid"] },
        )
        assertTrue(mainOnly.none { (it["params"] as Map<*, *>)["uuid"] == AirPlayInfoPlist.ALT_UUID })
    }

    @Test
    fun unavailableEventChannelKeepsOnlyLatestAppearance() {
        val pending = PendingAppearance()
        pending.update(CarPlayAppearance(true, true))
        assertFalse(pending.flush(false) { false })
        pending.update(CarPlayAppearance(false, false))
        val sent = mutableListOf<Map<String, Any?>>()
        assertTrue(pending.flush(false) { sent += it; true })
        assertEquals(AppearanceCommands.build(CarPlayAppearance(false, false), false), sent)
        assertTrue(pending.flush(false) { throw AssertionError("Already flushed") })
    }

    @Test
    fun latestAppearanceReplacesPartiallySentBatch() {
        val pending = PendingAppearance()
        pending.update(CarPlayAppearance(true, true))
        var calls = 0
        assertFalse(pending.flush(true) { ++calls < 2 })
        pending.update(CarPlayAppearance(false, true))
        val sent = mutableListOf<Map<String, Any?>>()
        assertTrue(pending.flush(true) { sent += it; true })
        assertEquals(AppearanceCommands.build(CarPlayAppearance(false, true), true), sent)
    }

    @Test
    fun disconnectDiscardsPendingAppearance() {
        val pending = PendingAppearance()
        pending.update(CarPlayAppearance(true, true))
        pending.clear()
        assertTrue(pending.flush(true) { throw AssertionError("Closed session must not replay") })
    }
}
