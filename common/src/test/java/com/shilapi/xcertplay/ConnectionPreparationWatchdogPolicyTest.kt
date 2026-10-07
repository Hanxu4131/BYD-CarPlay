package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class ConnectionPreparationWatchdogPolicyTest {
    private class Clock(var now: Long = 1_000L) {
        fun advance(millis: Long) { now += millis }
    }

    @Test fun wiredPreparationNeverRestartsAtOrAfterTheWirelessDeadline() {
        val clock = Clock()
        val controller = Any()
        val policy = ConnectionPreparationWatchdogPolicy()
        val attempt = policy.arm(controller, 0, clock.now)
        clock.advance(60_000)
        assertEquals(ConnectionPreparationWatchdogPolicy.Action.STOP,
            policy.action(attempt, controller, 0, clock.now, true, false, false, wireless = false))
        clock.advance(600_000)
        assertEquals(ConnectionPreparationWatchdogPolicy.Action.STOP,
            policy.action(attempt, controller, 0, clock.now, true, false, false, wireless = false))
    }

    @Test fun residualOldRendererOrBackgroundStateCannotProveANewSession() {
        val old = Any()
        val next = Any()
        // Session proof deliberately has no Texture-frame input: a teardown frame
        // can still update the shared view after the new controller was installed.
        val connected = ConnectionPreparationWatchdogPolicy.hasCurrentSession(
            next, next, false, false, old, true)
        assertFalse(connected)
        assertFalse(ConnectionPreparationWatchdogPolicy.hasCurrentSession(
            old, next, true, true, old, true))
        val policy = ConnectionPreparationWatchdogPolicy()
        val attempt = policy.arm(next, 1, 0)
        assertEquals(ConnectionPreparationWatchdogPolicy.Action.RESTART,
            policy.action(attempt, next, 1, 60_000, true, connected, false))
        // A real current controller session cancels even before the Host callback.
        assertTrue(ConnectionPreparationWatchdogPolicy.hasCurrentSession(
            next, next, true, false, next, false))
        assertTrue(ConnectionPreparationWatchdogPolicy.hasCurrentSession(
            next, next, false, true, next, false))
        assertTrue(ConnectionPreparationWatchdogPolicy.hasCurrentSession(
            next, next, false, false, next, true))
    }

    @Test fun deadlineIsSixtySecondsAndOnlyConsumedOnce() {
        val clock = Clock()
        val controller = Any()
        val policy = ConnectionPreparationWatchdogPolicy()
        val attempt = policy.arm(controller, 1, clock.now)
        clock.advance(59_999)
        assertEquals(ConnectionPreparationWatchdogPolicy.Action.WAIT,
            policy.action(attempt, controller, 1, clock.now, true, false, false))
        clock.advance(1)
        assertEquals(ConnectionPreparationWatchdogPolicy.Action.RESTART,
            policy.action(attempt, controller, 1, clock.now, true, false, false))
        assertEquals(ConnectionPreparationWatchdogPolicy.Action.STOP,
            policy.action(attempt, controller, 1, clock.now, true, false, false))
        val next = policy.arm(controller, 2, clock.now)
        clock.advance(60_000)
        assertEquals(ConnectionPreparationWatchdogPolicy.Action.RESTART,
            policy.action(next, controller, 2, clock.now, true, false, false))
    }

    @Test fun aRealSessionCancelsEvenWhenTheUiStillShowsPreparation() {
        val clock = Clock()
        val controller = Any()
        val policy = ConnectionPreparationWatchdogPolicy()
        val attempt = policy.arm(controller, 0, clock.now)
        clock.advance(60_000)
        assertEquals(ConnectionPreparationWatchdogPolicy.Action.STOP,
            policy.action(attempt, controller, 0, clock.now, true, true, false))
        clock.advance(600_000)
        assertEquals(ConnectionPreparationWatchdogPolicy.Action.STOP,
            policy.action(attempt, controller, 0, clock.now, true, false, false))
    }

    @Test fun oldControllerGenerationOrHostNeverRestarts() {
        val clock = Clock()
        val oldController = Any()
        val newController = Any()
        val policy = ConnectionPreparationWatchdogPolicy()
        val old = policy.arm(oldController, 0, clock.now)
        val next = policy.arm(newController, 1, clock.now)
        clock.advance(60_000)
        assertEquals(ConnectionPreparationWatchdogPolicy.Action.STOP,
            policy.action(old, newController, 1, clock.now, true, false, false))
        assertEquals(ConnectionPreparationWatchdogPolicy.Action.STOP,
            policy.action(next, oldController, 1, clock.now, true, false, false))
        assertEquals(ConnectionPreparationWatchdogPolicy.Action.STOP,
            policy.action(next, newController, 2, clock.now, true, false, false))
        assertEquals(ConnectionPreparationWatchdogPolicy.Action.STOP,
            policy.action(next, newController, 1, clock.now, false, false, false))
        assertEquals(ConnectionPreparationWatchdogPolicy.Action.STOP,
            policy.action(next, newController, 1, clock.now, true, false, false))
    }

    @Test fun explicitDisconnectAndPermissionOrNetworkRecoveryInvalidateTheDeadline() {
        val clock = Clock()
        val controller = Any()
        val policy = ConnectionPreparationWatchdogPolicy()
        val disconnected = policy.arm(controller, 0, clock.now)
        policy.cancel()
        clock.advance(60_000)
        assertEquals(ConnectionPreparationWatchdogPolicy.Action.STOP,
            policy.action(disconnected, controller, 0, clock.now, true, false, false))
        // Host supplies blocked for permission dialogs, Wi-Fi recovery, teardown,
        // settings and an already scheduled reconnect.
        val permission = policy.arm(controller, 1, clock.now)
        clock.advance(60_000)
        assertEquals(ConnectionPreparationWatchdogPolicy.Action.STOP,
            policy.action(permission, controller, 1, clock.now, true, false, true))
        assertEquals(ConnectionPreparationWatchdogPolicy.Action.STOP,
            policy.action(permission, controller, 1, clock.now, true, false, false))
        val recovered = policy.arm(controller, 2, clock.now)
        clock.advance(59_999)
        assertEquals(ConnectionPreparationWatchdogPolicy.Action.WAIT,
            policy.action(recovered, controller, 2, clock.now, true, false, false))
    }
}
