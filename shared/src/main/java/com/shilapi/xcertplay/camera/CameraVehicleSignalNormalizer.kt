package com.shilapi.xcertplay.camera

/** Raw values from the allow-listed read-only SDK probe. Unsupported fields are null. */
data class CameraVehicleRawSignals(
    val automaticGear: Int?,
    val brakeDepth: Int?,
    val globalTurnState: Int?,
    val leftTurnState: Int?,
    val rightTurnState: Int?,
    val steeringValue: Double?,
)

/** Explicitly documented value mappings; an absent mapping stays unknown, never false. */
data class CameraVehicleSignalEncoding(
    val driveGearValues: Set<Int> = emptySet(),
    val reverseGearValues: Set<Int> = emptySet(),
    val turnOffValues: Set<Int> = emptySet(),
    val turnOnValues: Set<Int> = emptySet(),
    /** OEM raw value multiplied by this factor yields degrees; null disables steering activation. */
    val steeringDegreesPerRawUnit: Double? = null,
    /** true means positive angle is right; false means positive angle is left. */
    val positiveSteeringMeansRight: Boolean = true,
)

/** Pure conversion boundary. No OEM value is interpreted unless its encoding is explicit. */
object CameraVehicleSignalNormalizer {
    /** These are the SDK's GEARBOX_AUTO_MODE_D/R constants, not REAL_LEVEL values. */
    val TangAutomaticGearEncoding = CameraVehicleSignalEncoding(
        driveGearValues = setOf(4), reverseGearValues = setOf(2),
        turnOffValues = setOf(0), turnOnValues = setOf(1),
        // OEM bodywork getter is an angle-valued Double with a documented +/-780 range.
        steeringDegreesPerRawUnit = 1.0,
        positiveSteeringMeansRight = true,
    )

    fun normalize(raw: CameraVehicleRawSignals, encoding: CameraVehicleSignalEncoding): CameraVehicleSignals {
        val drive = raw.automaticGear?.let { value ->
            when (value) {
                in encoding.driveGearValues -> true
                in encoding.reverseGearValues -> false
                1, 3, 5, 6 -> false // Known P/N/M/S SDK automatic-mode enum values.
                else -> null
            }
        }
        val reverse = raw.automaticGear?.let { value ->
            when (value) {
                in encoding.reverseGearValues -> true
                in encoding.driveGearValues -> false
                1, 3, 5, 6 -> false
                else -> null
            }
        }
        val (left, right) = when (raw.globalTurnState) {
            // SDK constant TURN_LIGHT_OFF=1 on LIGHT_TURN_SIGNAL_LIGHT_SWITCH_STATE.
            1 -> false to false
            // Directional fields can pulse during the flasher's off half-cycle. Preserve an
            // already-visible side with null there; only an asserted side opens a new view.
            2 -> (raw.leftTurnState?.takeIf { it == 1 }?.let { true }) to
                (raw.rightTurnState?.takeIf { it == 1 }?.let { true })
            null -> decodeTurn(raw.leftTurnState, encoding) to decodeTurn(raw.rightTurnState, encoding)
            else -> null to null // INVALID or an unrecognized aggregate state.
        }
        val scale = encoding.steeringDegreesPerRawUnit?.takeIf { it.isFinite() && it > 0.0 }
        val angle = raw.steeringValue?.takeIf { it.isFinite() && it in -780.0..780.0 }
        val degrees = if (scale == null || angle == null) null else angle * scale
        val leftDegrees = degrees?.let { signed ->
            (if (encoding.positiveSteeringMeansRight) -signed else signed).coerceAtLeast(0.0).toFloat()
        }
        val rightDegrees = degrees?.let { signed ->
            (if (encoding.positiveSteeringMeansRight) signed else -signed).coerceAtLeast(0.0).toFloat()
        }
        return CameraVehicleSignals(
            gearReverse = reverse,
            gearDrive = drive,
            carPlaySplit = null,
            brakeDepthPercent = raw.brakeDepth?.takeIf { it in 0..100 }?.toFloat(),
            leftTurn = left,
            rightTurn = right,
            steeringLeftDegrees = leftDegrees,
            steeringRightDegrees = rightDegrees,
        )
    }

    private fun decodeTurn(raw: Int?, encoding: CameraVehicleSignalEncoding): Boolean? {
        if (raw == null) return null
        val off = raw in encoding.turnOffValues
        val on = raw in encoding.turnOnValues
        return when {
            off && !on -> false
            on && !off -> true
            else -> null
        }
    }
}
