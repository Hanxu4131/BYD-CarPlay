package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LegacyL1WakeRecoveryPolicyTest {
    private val failed = LegacyL1WakeRecoveryPolicy.Snapshot(100L, true, true, true, false)
    @Test fun waitsForFiveSecondsAwakeAndThirtySecondsOfMissingBackend() {
        val policy = LegacyL1WakeRecoveryPolicy()
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(0, failed))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(4_999, failed))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(5_000, failed))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(34_999, failed))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.RECOVER, policy.observe(35_000, failed))
        assertTrue(policy.claim(100))
        policy.cancel()
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.SKIP, policy.observe(100_000, failed))
        assertFalse(policy.claim(100))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(100_001, failed.copy(wake = 200)))
    }
    @Test fun screenOffCancellationAndHealthyBackendResetTheGracePeriod() {
        val policy = LegacyL1WakeRecoveryPolicy()
        policy.observe(0, failed)
        policy.observe(5_000, failed)
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.HEALTHY, policy.observe(34_000, failed.copy(listening = true)))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(35_000, failed))
        policy.observe(64_000, failed.copy(awake = false))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(70_000, failed))
        policy.observe(75_000, failed)
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(104_999, failed))
    }
    @Test fun missingProcessTaskOrPortEvidenceNeverAuthorizesAStop() {
        for (sample in listOf(failed.copy(running = null), failed.copy(dashboard = false),
            failed.copy(listening = null), failed.copy(wake = null))) {
            val policy = LegacyL1WakeRecoveryPolicy()
            policy.observe(0, sample)
            assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(100_000, sample))
        }
    }
    @Test fun earlyMissingTaskAndUnknownPortMustBuildANewFailureGracePeriod() {
        val policy = LegacyL1WakeRecoveryPolicy()
        policy.observe(0, failed.copy(dashboard = false))
        policy.observe(5_000, failed.copy(running = false))
        policy.observe(20_000, failed)
        policy.observe(40_000, failed.copy(listening = null))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(45_000, failed))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(74_999, failed))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.RECOVER, policy.observe(75_000, failed))
    }
    @Test fun recognizesBothProcTcpFamiliesAndOnlyListenState() {
        val header = "  sl local_address rem_address st tx_queue\n"
        assertEquals(false, LegacyL1WakeRecoveryPolicy.listening(header))
        assertEquals(true, LegacyL1WakeRecoveryPolicy.listening(header + "0: 0100007F:AAF0 00000000:0000 0A 0"))
        assertEquals(true, LegacyL1WakeRecoveryPolicy.listening(header.replace("rem_address", "remote_address") + "0: 00000000000000000000000000000000:AAF0 00000000000000000000000000000000:0000 0A 0"))
        assertEquals(false, LegacyL1WakeRecoveryPolicy.listening(header + "0: 0100007F:AAF0 00000000:0000 01 0"))
        assertNull(LegacyL1WakeRecoveryPolicy.listening("cat: Permission denied"))
    }
    @Test fun oneKnownListeningFamilyProtectsHealthyCamerasDespiteTheOtherBeingUnreadable() {
        val power = "mWakefulness=Awake\nmWakefulnessChanging=false\nmLastWakeTime=100\nDisplay Power: state=ON"
        val tcp = "sl local_address rem_address st\n0: 0100007F:AAF0 00000000:0000 0A"
        val sample = LegacyL1WakeRecoveryPolicy.snapshot(power, "", "", tcp, "Permission denied")
        assertEquals(true, sample.listening)
        val policy = LegacyL1WakeRecoveryPolicy()
        policy.observe(0, sample)
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.HEALTHY, policy.observe(5_000, sample))
    }
    @Test fun malformedOrTruncatedPortTablesRemainUnknownAndCannotAuthorizeRecovery() {
        val header = "sl local_address rem_address st\n"
        for (table in listOf(header + "cat: Permission denied", header + "0: 0100007F:AAF0",
            header + "0: 0100007F:AAF0 00000000:0000", header + "0: bad bad 0A",
            header + "0: 0100007F:AAF0 00000000:0000 invalid")) {
            assertNull(LegacyL1WakeRecoveryPolicy.listening(table))
        }
        val policy = LegacyL1WakeRecoveryPolicy()
        policy.observe(0, failed)
        policy.observe(5_000, failed)
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT,
            policy.observe(100_000, failed.copy(listening = null)))
    }
    @Test fun parsesActualWakeSnapshotAndRejectsOneUnreadableTcpFamily() {
        val power = "  mWakefulness=Awake\n  mWakefulnessChanging=false\n  mLastWakeTime=171225610 (469885 ms ago)\nDisplay Power: state=ON\n"
        val tasks = "ActivityRecord{abc u0 l1tech.com.l1mini/.Dashboard, t277}"
        val header = "sl local_address rem_address st\n"
        val sample = LegacyL1WakeRecoveryPolicy.snapshot(power, tasks, "27165", header, header)
        assertEquals(171225610L, sample.wake)
        assertTrue(sample.awake && sample.running == true && sample.dashboard)
        assertEquals(false, sample.listening)
        assertNull(LegacyL1WakeRecoveryPolicy.snapshot(power, tasks, "27165", header, "denied").listening)
    }

    @Test fun deadProcessWithoutDashboardStartsOnlyAfterItsFullGracePeriodOncePerWake() {
        val sample = failed.copy(running = false, dashboard = false)
        val policy = LegacyL1WakeRecoveryPolicy()
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(0, sample))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(4_999, sample))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(5_000, sample))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(34_999, sample))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.START_ONLY, policy.observe(35_000, sample))
        assertTrue(policy.claim(100))
        policy.cancel()
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.SKIP, policy.observe(100_000, sample))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.SKIP, policy.observe(100_001, failed))
        assertFalse(policy.claim(100))
    }

    @Test fun processAppearingWithoutDashboardCancelsThePendingColdStart() {
        val policy = LegacyL1WakeRecoveryPolicy()
        val dead = failed.copy(running = false, dashboard = false)
        policy.observe(0, dead)
        policy.observe(5_000, dead)
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT,
            policy.observe(34_999, dead.copy(running = true)))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT,
            policy.observe(100_000, dead.copy(running = true)))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(100_001, failed))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.RECOVER, policy.observe(130_001, failed))
    }

    @Test fun unknownProcessOrTcpNeverAuthorizesColdStart() {
        for (sample in listOf(failed.copy(running = null, dashboard = false),
            failed.copy(running = false, dashboard = false, listening = null))) {
            val policy = LegacyL1WakeRecoveryPolicy()
            policy.observe(0, sample)
            policy.observe(5_000, sample)
            assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(100_000, sample))
        }
        val header = "sl local_address rem_address st\n"
        assertNull(LegacyL1WakeRecoveryPolicy.snapshot("", "", null, header, header).running)
        assertNull(LegacyL1WakeRecoveryPolicy.snapshot("", "", "pidof: denied", header, header).running)
        assertEquals(false, LegacyL1WakeRecoveryPolicy.snapshot("", "", "", header, header).running)
    }

    @Test fun acceptsDeclaredProcessNamesAndRejectsShellSyntax() {
        assertEquals("com.byd.notice", LegacyL1WakeRecoveryPolicy.safeProcessName("com.byd.notice"))
        assertEquals("l1tech.com.l1mini:camera", LegacyL1WakeRecoveryPolicy.safeProcessName("l1tech.com.l1mini:camera"))
        for (name in listOf(null, "", "com.byd.notice;am force-stop x", "com.byd.notice\n", "$(id)", "-x"))
            assertNull(LegacyL1WakeRecoveryPolicy.safeProcessName(name))
    }

    @Test fun completedMapStartsADeadBackendAfterStableAwakeWithoutThirtySecondDelay() {
        val policy = LegacyL1WakeRecoveryPolicy()
        val dead = failed.copy(running = false, dashboard = false)
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(0, dead, true))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(4_999, dead, true))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.START_ONLY, policy.observe(5_000, dead, true))
        assertTrue(policy.claim(100))
        policy.cancel()
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.SKIP, policy.observe(10_000, dead, true))
        assertFalse(policy.claim(100))
    }

    @Test fun completedMapStartsMissingServiceEvenWhenL1ProcessIsAlive() {
        val policy = LegacyL1WakeRecoveryPolicy()
        val idle = failed.copy(dashboard = false, bootService = false)
        policy.observe(0, idle, true)
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.START_ONLY, policy.observe(5_000, idle, true))
    }

    @Test fun completedMapGivesInitializingServiceThirtySecondsThenOnlyWakesIt() {
        val policy = LegacyL1WakeRecoveryPolicy()
        val initializing = failed.copy(dashboard = false, bootService = true)
        policy.observe(0, initializing, true)
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(5_000, initializing, true))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(34_999, initializing, true))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.START_ONLY, policy.observe(35_000, initializing, true))
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.START_ONLY,
            policy.observe(36_000, initializing.copy(dashboard = true), true))
    }

    @Test fun completedMapNeverWakesHealthyOrUnknownL1AndSleepCancelsItsGracePeriod() {
        for (sample in listOf(failed.copy(listening = null, bootService = false),
            failed.copy(running = null, bootService = false), failed.copy(dashboard = false, bootService = null))) {
            val policy = LegacyL1WakeRecoveryPolicy()
            policy.observe(0, sample, true)
            policy.observe(5_000, sample, true)
            assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(100_000, sample, true))
        }
        val policy = LegacyL1WakeRecoveryPolicy()
        val initializing = failed.copy(dashboard = false, bootService = true)
        policy.observe(0, initializing, true)
        policy.observe(5_000, initializing, true)
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.HEALTHY,
            policy.observe(34_999, initializing.copy(listening = true), true))
        policy.observe(35_000, initializing.copy(awake = false), true)
        policy.observe(36_000, initializing, true)
        policy.observe(41_000, initializing, true)
        assertEquals(LegacyL1WakeRecoveryPolicy.Decision.WAIT, policy.observe(70_999, initializing, true))
    }

    @Test fun bootServiceDiagnosticsRequireComponentRecordOrExplicitFilteredAbsence() {
        val header = "ACTIVITY MANAGER SERVICES (dumpsys activity services)\n"
        assertEquals(true, LegacyL1WakeRecoveryPolicy.bootServicePresent(header +
            "  User 0 active services:\n  * ServiceRecord{2f1d7ec u0 l1tech.com.l1mini/.L1BootService}\n    app=ProcessRecord{abc}"))
        assertEquals(false, LegacyL1WakeRecoveryPolicy.bootServicePresent(header + "  (nothing)\n"))
        for (text in listOf(null, "", "Permission denied", header, header + "  ServiceRecord{truncated",
            header + "intent={cmp=l1tech.com.l1mini/.L1BootService}",
            header + "Error: truncated\n  (nothing)"))
            assertNull(LegacyL1WakeRecoveryPolicy.bootServicePresent(text))
    }
}
