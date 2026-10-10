package com.shilapi.xcertplay.airplay

/** Numeric summary of an applied clock correction; contains no peer or media identifiers. */
data class NtpCorrectionDiagnostic(
    val isStep: Boolean,
    val appliedCorrectionMs: Double,
    val selectedRttMs: Double,
    val cumulativeCorrectionMs: Double,
    val correctionCount: Long,
    val stepCount: Long,
    val coalescedCorrectionCount: Long,
)

/** Coalesces clock updates so diagnostics cannot become a per-response log stream. */
internal class NtpCorrectionDiagnostics(
    private val minimumIntervalMs: Long = MINIMUM_INTERVAL_MS,
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
) {
    private var lastReportedAtMs: Long? = null
    private var cumulativeCorrectionNs = 0L
    private var correctionCount = 0L
    private var stepCount = 0L
    private var coalescedCorrectionCount = 0L

    init {
        require(minimumIntervalMs > 0L)
    }

    /** Returns a snapshot at most once per interval, retaining counts for suppressed updates. */
    fun record(appliedCorrectionNs: Long, isStep: Boolean, selectedRttMs: Double): NtpCorrectionDiagnostic? {
        cumulativeCorrectionNs += appliedCorrectionNs
        correctionCount++
        if (isStep) stepCount++

        val now = nowMs()
        val previous = lastReportedAtMs
        if (previous != null && now - previous < minimumIntervalMs) {
            coalescedCorrectionCount++
            return null
        }

        val diagnostic = NtpCorrectionDiagnostic(
            isStep = isStep,
            appliedCorrectionMs = appliedCorrectionNs / NANOS_PER_MILLISECOND.toDouble(),
            selectedRttMs = selectedRttMs,
            cumulativeCorrectionMs = cumulativeCorrectionNs / NANOS_PER_MILLISECOND.toDouble(),
            correctionCount = correctionCount,
            stepCount = stepCount,
            coalescedCorrectionCount = coalescedCorrectionCount,
        )
        lastReportedAtMs = now
        coalescedCorrectionCount = 0L
        return diagnostic
    }

    private companion object {
        const val MINIMUM_INTERVAL_MS = 30_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
