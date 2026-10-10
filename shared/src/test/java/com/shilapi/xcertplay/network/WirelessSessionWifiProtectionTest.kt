package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class WirelessSessionWifiProtectionTest {
    private class FakeLease : WirelessSessionWifiProtection.Lease {
        override var isHeld = false
        var acquired = 0
        var released = 0
        var rejectAcquire = false
        var rejectRelease = false
        var failAfterAcquire = false
        override fun acquire() {
            acquired++
            if (rejectAcquire) throw SecurityException("private details must not be logged")
            isHeld = true
            if (failAfterAcquire) throw IllegalStateException("partial acquisition")
        }
        override fun release() {
            if (rejectRelease) throw IllegalStateException("release failed")
            released++
            isHeld = false
        }
    }

    @Test fun wiredAndIdleDoNotAcquireAndPre29OnlyRequestsHighPerf() {
        val modes = mutableListOf<WirelessSessionWifiProtection.Mode>()
        val lease = FakeLease()
        val protection = WirelessSessionWifiProtection(28, { modes += it; lease })
        protection.sessionActive(Any(), false)
        assertTrue(modes.isEmpty())
        protection.sessionActive(Any(), true)
        assertEquals(listOf(WirelessSessionWifiProtection.Mode.HIGH_PERF), modes)
        protection.close()
        assertFalse(lease.isHeld)
    }

    @Test fun duplicateActiveAndOldSessionEndCannotReleaseReplacementOwner() {
        val locks = WirelessSessionWifiProtection.Mode.entries.associateWith { FakeLease() }
        val protection = WirelessSessionWifiProtection(29, { locks[it] })
        val old = Any(); val current = Any()
        protection.sessionActive(old, true)
        protection.sessionActive(old, true)
        protection.sessionActive(current, true)
        protection.sessionEnded(old)
        locks.values.forEach { assertTrue(it.isHeld); assertEquals(1, it.acquired) }
        protection.sessionEnded(current)
        protection.sessionEnded(current)
        locks.values.forEach { assertFalse(it.isHeld); assertEquals(1, it.released) }
    }

    @Test fun controllerClosePermanentlyRejectsLateActivation() {
        val lock = FakeLease()
        val protection = WirelessSessionWifiProtection(28, { lock })
        protection.sessionActive(Any(), true)
        protection.close()
        protection.sessionActive(Any(), true)
        protection.close()
        assertEquals(1, lock.acquired)
        assertEquals(1, lock.released)
        assertFalse(lock.isHeld)
    }

    @Test fun eitherModeCanFailWithoutReleasingTheSuccessfulMode() {
        for (failed in WirelessSessionWifiProtection.Mode.entries) {
            val locks = WirelessSessionWifiProtection.Mode.entries.associateWith { FakeLease() }
            locks.getValue(failed).rejectAcquire = true
            val logs = mutableListOf<String>()
            val protection = WirelessSessionWifiProtection(29, { locks[it] }, { logs += it })
            protection.sessionActive(Any(), true)
            locks.forEach { (mode, lock) -> assertEquals(mode != failed, lock.isHeld) }
            assertTrue(logs.any { it.contains("failure=SecurityException") })
            assertFalse(logs.any { it.contains("private details") })
            protection.close()
            locks.values.forEach { assertFalse(it.isHeld) }
        }
    }

    @Test fun partiallyAcquiredFailureStillRetainsHandleForCleanup() {
        val lock = FakeLease().apply { failAfterAcquire = true }
        val protection = WirelessSessionWifiProtection(28, { lock })
        val owner = Any()
        protection.sessionActive(owner, true)
        assertTrue(lock.isHeld)
        protection.sessionEnded(owner)
        assertFalse(lock.isHeld)
    }

    @Test fun unavailableManagerAndDiagnosticsFailureCannotEscape() {
        val protection = WirelessSessionWifiProtection(29, { null }, { throw IllegalStateException() })
        val owner = Any()
        protection.sessionActive(owner, true)
        protection.sessionEnded(owner)
        protection.close()
    }

    @Test fun closeFromDiagnosticCallbackCannotAcquireTheNextMode() {
        val lock = FakeLease()
        var created = 0
        lateinit var protection: WirelessSessionWifiProtection
        protection = WirelessSessionWifiProtection(29, { created++; lock }, {
            if (it.contains("held=true")) protection.close()
        })
        protection.sessionActive(Any(), true)
        assertEquals(1, created)
        assertFalse(lock.isHeld)
        protection.sessionActive(Any(), true)
        assertEquals(1, created)
    }

    @Test fun releaseFailureDoesNotPreventOtherModeCleanupAndCloseCanRetry() {
        val locks = WirelessSessionWifiProtection.Mode.entries.associateWith { FakeLease() }
        val high = locks.getValue(WirelessSessionWifiProtection.Mode.HIGH_PERF)
        high.rejectRelease = true
        val protection = WirelessSessionWifiProtection(29, { locks[it] })
        protection.sessionActive(Any(), true)
        protection.close()
        assertTrue(high.isHeld)
        assertFalse(locks.getValue(WirelessSessionWifiProtection.Mode.LOW_LATENCY).isHeld)
        high.rejectRelease = false
        protection.close()
        assertFalse(high.isHeld)
        protection.sessionActive(Any(), true)
        assertEquals(1, high.acquired)
    }
}
