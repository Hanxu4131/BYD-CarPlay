package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class LegacyClusterGuidanceTest {
    private val combined = CarPlayClusterDisplay.Content.INSTRUMENTS

    @Before fun reset() {
        CarPlayGlance.setConnected(true)
        CarPlayGlance.setConnected(false)
    }

    @After fun clear() = reset()

    private fun send(id: Int, body: Iap2BodyBuilder.() -> Unit) {
        CarPlayGlance.onFrame(Iap2Messages.buildRaw(id, body))
    }

    private fun navigate(type: Int = 2) {
        CarPlayGlance.setConnected(true)
        send(0x5202) { u16(1, 7); u8(3, type); string(4, "下一条道路") }
        send(0x5201) { u8(1, 1); u32(0x0a, 350); u16List(0x0d, listOf(7)) }
    }

    @Test fun onlyCombinedModeShowsAnActualPhoneInstruction() {
        assertFalse(LegacyClusterGuidance.visible(combined, CarPlayGlance.snapshot()))
        CarPlayGlance.setConnected(true)
        assertFalse(LegacyClusterGuidance.visible(combined, CarPlayGlance.snapshot()))
        // A cached maneuver by itself is not an active route.
        send(0x5202) { u16(1, 7); u8(3, 2) }
        assertFalse(LegacyClusterGuidance.visible(combined, CarPlayGlance.snapshot()))
        navigate()
        val state = CarPlayGlance.snapshot()
        assertTrue(LegacyClusterGuidance.visible(combined, state))
        assertEquals(350, state.distanceMeters)
        assertEquals("下一条道路", state.road)
        assertEquals(R.drawable.ic_maneuver_right, LegacyClusterGuidance.arrow(state.maneuverType!!, state.drivingSide))
        assertFalse(LegacyClusterGuidance.visible(CarPlayClusterDisplay.Content.MAP, state))
        assertFalse(LegacyClusterGuidance.visible(CarPlayClusterDisplay.Content.TURN_CARD, state))
    }

    @Test fun arrivalAndDisconnectRemoveThePreviousInstruction() {
        navigate()
        send(0x5201) { u8(1, 2) }
        assertFalse(LegacyClusterGuidance.visible(combined, CarPlayGlance.snapshot()))
        navigate()
        CarPlayGlance.setConnected(false)
        assertFalse(LegacyClusterGuidance.visible(combined, CarPlayGlance.snapshot()))
        assertNull(CarPlayGlance.snapshot().maneuverType)
    }

    @Test fun pollingHidesExpiredGuidanceWithoutAnyNewPhoneFrame() {
        withRouteClock { advance ->
            navigate()
            assertTrue(LegacyClusterGuidance.visible(combined, CarPlayGlance.snapshot()))
            advance(119_000_000_000L)
            assertTrue(LegacyClusterGuidance.visible(combined, CarPlayGlance.snapshot()))
            advance(1_000_000_000L)
            val state = CarPlayGlance.snapshot()
            assertTrue(state.connected)
            assertFalse(LegacyClusterGuidance.visible(combined, state))
            assertNull(state.maneuverType)
        }
    }

    @Test fun aPersistentEmptyRouteListHidesTheCardAfterItsGracePeriod() {
        withRouteClock { advance ->
            navigate()
            send(0x5201) { u16List(0x0d, emptyList()) }
            advance(2_000_000_000L)
            assertTrue(LegacyClusterGuidance.visible(combined, CarPlayGlance.snapshot()))
            advance(1_000_000_000L)
            assertFalse(LegacyClusterGuidance.visible(combined, CarPlayGlance.snapshot()))
        }
    }

    @Test fun unknownPhoneCodesUseANeutralIconRatherThanInventingStraightAhead() {
        navigate(255)
        val state = CarPlayGlance.snapshot()
        assertTrue(LegacyClusterGuidance.visible(combined, state))
        assertEquals(R.drawable.ic_dp_navigation, LegacyClusterGuidance.arrow(state.maneuverType!!, state.drivingSide))
        assertNotEquals(R.drawable.ic_maneuver_straight, LegacyClusterGuidance.arrow(255, 0))
        assertEquals(R.drawable.ic_maneuver_u_turn_right, LegacyClusterGuidance.arrow(4, 1))
    }

    @Test fun cardFollowsItsIndependentMovementAndScaleInsideTheActualMap() {
        val map = LegacyClusterLayout.Plan(480, 180, 960, 360)
        val initial = LegacyClusterGuidance.placement(map, LegacyClusterTurnArea.Settings())
        val moved = LegacyClusterGuidance.placement(map, LegacyClusterTurnArea.Settings(54.5, 34.92))
        assertTrue(moved.left > initial.left && moved.top > initial.top)
        assertEquals(initial.width, moved.width)
        assertEquals(initial.height, moved.height)
        val enlarged = LegacyClusterGuidance.placement(map, LegacyClusterTurnArea.Settings(scalePercent = 105))
        assertTrue(enlarged.width > initial.width && enlarged.height > initial.height)
        assertTrue(enlarged.left >= map.left && enlarged.top >= map.top)
        assertTrue(enlarged.left + enlarged.width <= map.left + map.width)
        assertTrue(enlarged.top + enlarged.height <= map.top + map.height)
    }

    // Advance the existing parser's monotonic clock; no sleeps or synthetic UI snapshots.
    private fun withRouteClock(test: ((Long) -> Unit) -> Unit) {
        val route = CarPlayGlance.javaClass.getDeclaredField("route").apply { isAccessible = true }.get(CarPlayGlance)
        val clock = route.javaClass.getDeclaredField("nanoTime").apply { isAccessible = true }
        val original = clock.get(route)
        var now = 1_000_000_000L
        try {
            clock.set(route, { now })
            test { now += it }
        } finally {
            clock.set(route, original)
        }
    }
}
