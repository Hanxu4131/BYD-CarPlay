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
        for (sample in listOf(failed.copy(running = false), failed.copy(dashboard = false),
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
        assertTrue(sample.awake && sample.running && sample.dashboard)
        assertEquals(false, sample.listening)
        assertNull(LegacyL1WakeRecoveryPolicy.snapshot(power, tasks, "27165", header, "denied").listening)
    }
}
