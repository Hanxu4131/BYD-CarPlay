package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class LegacyL1DashboardRecoveryPolicyTest {
    private val healthy = LegacyL1WakeRecoveryPolicy.Snapshot(100, true, false, null, true)

    @Test fun missingDashboardRequiresKnownListeningAwakeAndLaunchableActivity() {
        val policy = LegacyL1DashboardRecoveryPolicy()
        for (sample in listOf(healthy.copy(listening = false), healthy.copy(listening = null),
            healthy.copy(awake = false), healthy.copy(wake = null), healthy.copy(dashboard = true))) {
            assertEquals(LegacyL1DashboardRecoveryPolicy.Decision.WAIT, policy.observe(sample, true, true))
            assertFalse(policy.claim(sample, true, true))
        }
        assertFalse(policy.claim(healthy, false, true))
        assertEquals(LegacyL1DashboardRecoveryPolicy.Decision.START, policy.observe(healthy, true, true))
    }

    @Test fun cancelledOwnerCannotStartOrConsumeTheWakeBudget() {
        val policy = LegacyL1DashboardRecoveryPolicy()
        assertEquals(LegacyL1DashboardRecoveryPolicy.Decision.WAIT, policy.observe(healthy, true, false))
        assertFalse(policy.claim(healthy, true, false))
        assertTrue(policy.claim(healthy, true, true))
    }

    @Test fun repeatedMissingDashboardStartsOnceUntilANewWake() {
        val policy = LegacyL1DashboardRecoveryPolicy()
        assertTrue(policy.claim(healthy, true, true))
        assertFalse(policy.claim(healthy, true, true))
        assertEquals(LegacyL1DashboardRecoveryPolicy.Decision.ALREADY_ATTEMPTED,
            policy.observe(healthy, true, true))
        assertEquals(LegacyL1DashboardRecoveryPolicy.Decision.WAIT,
            policy.observe(healthy.copy(dashboard = true), true, true))
        assertTrue(policy.claim(healthy.copy(wake = 200), true, true))
    }

    @Test fun dashboardLaunchDoesNotConsumeBackendRecoveryBudget() {
        val dashboardPolicy = LegacyL1DashboardRecoveryPolicy()
        assertTrue(dashboardPolicy.claim(healthy, true, true))
        val backendPolicy = LegacyL1WakeRecoveryPolicy()
        val dead = healthy.copy(running = false, listening = false)
        backendPolicy.observe(0, dead)
        backendPolicy.observe(5_000, dead)
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.START_ONLY, backendPolicy.observe(35_000, dead))
        assertTrue(backendPolicy.claim(100))
    }
}
