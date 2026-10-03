package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class LegacyL1WakeRecoveryBudgetTest {
    @Test fun anAlreadyPersistedWakeCannotRestartAfterProcessRecreation() {
        var writes = 0
        assertEquals(LegacyL1WakeRecoveryBudget.Result.ALREADY_ATTEMPTED,
            LegacyL1WakeRecoveryBudget.claim(12, 1234, 12, 1234) { writes++; true })
        assertEquals(0, writes)
    }
    @Test fun aNewBootOrWakeMustCommitBeforeItCanRestart() {
        var writes = 0
        for ((boot, wake) in listOf(13 to 1234L, 12 to 5678L)) {
            assertEquals(LegacyL1WakeRecoveryBudget.Result.PERSISTED,
                LegacyL1WakeRecoveryBudget.claim(boot, wake, 12, 1234) { writes++; true })
        }
        assertEquals(2, writes)
    }
    @Test fun commitFailureOrExceptionNeverAuthorizesTheRestart() {
        assertEquals(LegacyL1WakeRecoveryBudget.Result.PERSIST_FAILED,
            LegacyL1WakeRecoveryBudget.claim(12, 5678, 12, 1234) { false })
        assertEquals(LegacyL1WakeRecoveryBudget.Result.PERSIST_FAILED,
            LegacyL1WakeRecoveryBudget.claim(12, 5678, 12, 1234) { error("storage unavailable") })
    }
    @Test fun unavailableBootCountExplicitlyFallsBackToSessionBudget() {
        var writes = 0
        for (boot in listOf(null, -1)) {
            assertEquals(LegacyL1WakeRecoveryBudget.Result.SESSION_ONLY,
                LegacyL1WakeRecoveryBudget.claim(boot, 5678, 12, 1234) { writes++; true })
        }
        assertEquals(0, writes)
    }
}
