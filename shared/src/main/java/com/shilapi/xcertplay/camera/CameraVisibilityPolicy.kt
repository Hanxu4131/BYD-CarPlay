package com.shilapi.xcertplay.camera

/** Null signal means stale, invalid, or unavailable; an adapter must normalize OEM values first. */
data class CameraVehicleSignals(
    val gearReverse: Boolean?,
    val gearDrive: Boolean?,
    val carPlaySplit: Boolean?,
    val brakeDepthPercent: Float?,
    val leftTurn: Boolean?,
    val rightTurn: Boolean?,
    /** Directional magnitudes in degrees, already converted from the OEM steering value. */
    val steeringLeftDegrees: Float?,
    val steeringRightDegrees: Float?,
)

data class CameraVisibilityState(
    val visibleViews: Set<CameraView> = emptySet(),
    /** Top-most first. Only the independently overlapping front windows are listed. */
    val frontZOrder: List<CameraView> = emptyList(),
)

/** Pure state policy; it has no Android, camera SDK, or vehicle API dependency. */
class CameraVisibilityPolicy {
    private enum class Trigger { ACTIVE, CLEARED, UNKNOWN }

    private var rearVisible = false
    private val frontVisible = linkedMapOf(
        CameraView.LEFT_FRONT to false,
        CameraView.RIGHT_FRONT to false,
    )
    private val activatedAt = mutableMapOf(
        CameraView.LEFT_FRONT to -1L,
        CameraView.RIGHT_FRONT to -1L,
    )
    private var activationSequence = 0L

    fun update(settings: CameraSettings.Values, signals: CameraVehicleSignals): CameraVisibilityState {
        val safe = settings.sanitized()
        validateSignals(signals)
        if (!safe.enabled) {
            reset()
            return CameraVisibilityState()
        }

        val rearTrigger = signals.gearReverse == true ||
            (signals.brakeDepthPercent != null && signals.brakeDepthPercent >= safe.brakeDepthThreshold)
        val rearCleared = signals.gearReverse == false && signals.brakeDepthPercent != null &&
            signals.brakeDepthPercent < safe.brakeDepthThreshold
        if (rearTrigger) rearVisible = true
        else if (rearCleared) rearVisible = false

        val gateOpen = signals.gearDrive == true && signals.carPlaySplit == true
        val gateClosed = signals.gearDrive == false || signals.carPlaySplit == false
        if (gateClosed) {
            frontVisible.keys.forEach { frontVisible[it] = false }
        } else if (gateOpen) {
            val triggers = mapOf(
                CameraView.LEFT_FRONT to sideTrigger(
                    signals.leftTurn, signals.steeringLeftDegrees, safe.steeringThresholdDegrees,
                ),
                CameraView.RIGHT_FRONT to sideTrigger(
                    signals.rightTurn, signals.steeringRightDegrees, safe.steeringThresholdDegrees,
                ),
            )
            val newlyActive = triggers.filter { (view, trigger) ->
                trigger == Trigger.ACTIVE && frontVisible[view] != true
            }.keys
            if (newlyActive.isNotEmpty()) {
                activationSequence++
                newlyActive.forEach { activatedAt[it] = activationSequence }
            }
            triggers.forEach { (view, trigger) ->
                when (trigger) {
                    Trigger.ACTIVE -> frontVisible[view] = true
                    Trigger.CLEARED -> frontVisible[view] = false
                    Trigger.UNKNOWN -> Unit // stale data neither opens nor closes nor reorders a window
                }
            }
        } // Stale D/split gate preserves current windows but cannot activate a new one.

        val zOrder = frontVisible.filterValues { it }.keys.sortedWith(
            compareByDescending<CameraView> { activatedAt[it] ?: -1L }
                .thenBy { if (it == CameraView.LEFT_FRONT) 0 else 1 },
        )
        val visible = linkedSetOf<CameraView>()
        visible += CameraView.LEFT_REAR
        visible += CameraView.RIGHT_REAR
        if (rearVisible) visible += CameraView.REAR
        visible += zOrder
        return CameraVisibilityState(visible, zOrder)
    }

    fun reset() {
        rearVisible = false
        frontVisible.keys.forEach { frontVisible[it] = false }
        activatedAt.keys.forEach { activatedAt[it] = -1L }
        activationSequence = 0L
    }

    private fun sideTrigger(turn: Boolean?, steeringDegrees: Float?, thresholdDegrees: Float): Trigger {
        if (turn == true || (steeringDegrees != null && steeringDegrees >= thresholdDegrees)) return Trigger.ACTIVE
        if (turn == false && steeringDegrees != null && steeringDegrees < thresholdDegrees) return Trigger.CLEARED
        return Trigger.UNKNOWN
    }

    private fun validateSignals(signals: CameraVehicleSignals) {
        require(signals.brakeDepthPercent == null ||
            (signals.brakeDepthPercent.isFinite() && signals.brakeDepthPercent in 0f..100f)) {
            "brake depth must be adapter-normalized to 0..100 or null"
        }
        require(listOf(signals.steeringLeftDegrees, signals.steeringRightDegrees).all {
            it == null || (it.isFinite() && it >= 0f)
        }) { "steering input must be a non-negative degree magnitude or null" }
    }
}
