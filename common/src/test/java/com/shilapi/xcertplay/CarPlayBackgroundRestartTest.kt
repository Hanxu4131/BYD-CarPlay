package com.shilapi.xcertplay

import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.robolectric.Robolectric
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarPlayBackgroundRestartTest {
    @After fun tearDown() {
        field("stopping", false)
        CarPlayBackgroundSession.clear()
    }

    @Test fun controlRestartKeepsTheHostDisplaySizeAndRejectsRepeatedTap() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        fun hostField(name: String) = host.javaClass.getDeclaredField(name).apply { isAccessible = true }
        fun hostMethod(name: String) = host.javaClass.getDeclaredMethod(name).apply { isAccessible = true }
        (hostField("teardownExecutor").get(host) as ExecutorService).shutdownNow()
        val teardown = PausedExecutorService()
        hostField("teardownExecutor").set(host, teardown)
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1284, 990)
        hostField("activeDisplaySize").set(host, size)
        bind(host, { hostMethod("canRestartFromControls").invoke(host) as Boolean }) {
            hostMethod("restartFromControls").invoke(host)
        }
        try {
            // A retained owner can restart even when its controller is missing after a failed connection.
            assertTrue(CarPlayBackgroundSession.restart())
            assertSame(size, hostField("activeDisplaySize").get(host))
            assertEquals(1, hostField("restartGeneration").get(host))
            assertEquals(true, hostField("handshakeResetInProgress").get(host))
            assertFalse(CarPlayBackgroundSession.restart())
            assertEquals(1, hostField("restartGeneration").get(host))
        } finally {
            (hostField("shuttingDown").get(host) as AtomicBoolean).set(true)
            teardown.shutdownNow()
            (hostField("airPlayCommandExecutor").get(host) as ExecutorService).shutdownNow()
        }
    }

    @Test fun repeatedTapDuringTeardownOnlyRestartsOnce() {
        var busy = false
        var restarts = 0
        bind(Any(), { !busy }) { busy = true; restarts++ }
        assertTrue(CarPlayBackgroundSession.restart())
        assertFalse(CarPlayBackgroundSession.canRestart())
        assertFalse(CarPlayBackgroundSession.restart())
        assertEquals(1, restarts)
        busy = false
        assertTrue(CarPlayBackgroundSession.canRestart())
    }

    @Test fun ownerTransferRoutesRestartToTheCurrentHost() {
        val firstOwner = Any()
        val secondOwner = Any()
        var firstRestarts = 0
        var secondRestarts = 0
        bind(firstOwner, { true }) { firstRestarts++ }
        bind(secondOwner, { true }) { secondRestarts++ }
        assertTrue(CarPlayBackgroundSession.restart())
        assertFalse(CarPlayBackgroundSession.isOwner(firstOwner))
        assertTrue(CarPlayBackgroundSession.isOwner(secondOwner))
        assertEquals(0, firstRestarts)
        assertEquals(1, secondRestarts)
    }

    @Test fun teardownRetainsTheOwnerButDisconnectDropsRestartRouting() {
        val owner = Any()
        var busy = true
        bind(owner, { !busy }) {}
        CarPlayBackgroundSession.clear(keepOwner = true)
        assertTrue(CarPlayBackgroundSession.isOwner(owner))
        assertFalse(CarPlayBackgroundSession.restart())
        busy = false
        assertTrue(CarPlayBackgroundSession.canRestart())
        CarPlayBackgroundSession.clear()
        assertFalse(CarPlayBackgroundSession.canRestart())
        assertFalse(CarPlayBackgroundSession.restart())
    }

    @Test fun disconnectInProgressBlocksRestart() {
        var restarts = 0
        bind(Any(), { true }) { restarts++ }
        field("stopping", true)
        assertFalse(CarPlayBackgroundSession.restart())
        assertEquals(0, restarts)
    }

    private fun bind(owner: Any, available: () -> Boolean, restart: () -> Unit) {
        field("owner", owner)
        field("restartAvailable", available)
        field("restartAction", restart)
    }

    private fun field(name: String, value: Any?) {
        CarPlayBackgroundSession::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(CarPlayBackgroundSession, value)
    }
}
