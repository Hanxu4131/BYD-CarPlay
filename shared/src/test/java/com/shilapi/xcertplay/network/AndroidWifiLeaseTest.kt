package com.shilapi.xcertplay.network

import android.net.wifi.WifiManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AndroidWifiLeaseTest {
    @Test fun android29LocksSupportBothModesAndDoNotAccumulateReferences() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(WifiManager::class.java)
        val locks = WirelessSessionWifiProtection.Mode.entries.map {
            manager.createWifiLock(it.value, "DiPlay-test-${it.name}")
        }
        val leases = locks.map(::AndroidWifiLease)
        leases.forEach { it.acquire(); it.acquire() }
        locks.forEach { assertTrue(it.isHeld) }
        leases.forEach { it.release() }
        locks.forEach { assertFalse(it.isHeld) }
    }
}
