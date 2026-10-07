package com.shilapi.xcertplay

/** Creating a missing Dashboard requires a live backend and is attempted once per wake. */
internal class LegacyL1DashboardRecoveryPolicy {
    enum class Decision { WAIT, START, ALREADY_ATTEMPTED }
    private var attemptedWake: Long? = null

    fun observe(sample: LegacyL1WakeRecoveryPolicy.Snapshot, launchable: Boolean, active: Boolean): Decision {
        if (!active || sample.wake == null || !sample.awake || sample.dashboard ||
            sample.listening != true || !launchable) return Decision.WAIT
        return if (attemptedWake == sample.wake) Decision.ALREADY_ATTEMPTED else Decision.START
    }

    fun claim(sample: LegacyL1WakeRecoveryPolicy.Snapshot, launchable: Boolean, active: Boolean): Boolean {
        if (observe(sample, launchable, active) != Decision.START) return false
        attemptedWake = sample.wake
        return true
    }
}
