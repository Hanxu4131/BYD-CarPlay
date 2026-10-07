package com.shilapi.xcertplay.camera

import org.junit.Assert.*
import org.junit.Test

class CameraVehicleSignalNormalizerTest {
    private val tang = CameraVehicleSignalNormalizer.TangAutomaticGearEncoding

    @Test fun automaticGearModeMapsOnlyKnownDAndRValues() {
        val d = CameraVehicleSignalNormalizer.normalize(CameraVehicleRawSignals(4, 0, 1, 0, 0, 0.0), tang)
        assertEquals(true, d.gearDrive)
        assertEquals(false, d.gearReverse)
        val r = CameraVehicleSignalNormalizer.normalize(CameraVehicleRawSignals(2, 0, 1, 0, 0, 0.0), tang)
        assertEquals(false, r.gearDrive)
        assertEquals(true, r.gearReverse)
        val p = CameraVehicleSignalNormalizer.normalize(CameraVehicleRawSignals(1, 0, 1, 0, 0, 0.0), tang)
        assertEquals(false, p.gearDrive)
        assertEquals(false, p.gearReverse)
        val unsupported = CameraVehicleSignalNormalizer.normalize(CameraVehicleRawSignals(65535, 0, 1, 0, 0, 0.0), tang)
        assertNull(unsupported.gearDrive)
        assertNull(unsupported.gearReverse)
    }

    @Test fun brakeMustBeWithinDocumentedPercentRangeAndTurnOffNeedsExplicitAggregateEvidence() {
        val supported = CameraVehicleSignalNormalizer.normalize(CameraVehicleRawSignals(4, 40, 1, null, null, null), tang)
        assertEquals(40f, requireNotNull(supported.brakeDepthPercent), 0f)
        assertEquals(false, supported.leftTurn)
        assertEquals(false, supported.rightTurn)
        val unsupported = CameraVehicleSignalNormalizer.normalize(CameraVehicleRawSignals(4, 101, null, null, null, null), tang)
        assertNull(unsupported.brakeDepthPercent)
        assertNull(unsupported.leftTurn)
        assertNull(unsupported.rightTurn)
        val invalidAggregate = CameraVehicleSignalNormalizer.normalize(CameraVehicleRawSignals(4, 0, 0, 1, 1, null), tang)
        assertNull(invalidAggregate.leftTurn)
        assertNull(invalidAggregate.rightTurn)
    }

    @Test fun directionalTurnAndSteeringAreNormalizedOnlyByExplicitMappings() {
        val encoding = tang.copy(turnOffValues = setOf(0), turnOnValues = setOf(1))
        val raw = CameraVehicleRawSignals(4, 20, 2, 1, 0, 12.5)
        val signal = CameraVehicleSignalNormalizer.normalize(raw, encoding)
        assertEquals(true, signal.leftTurn)
        assertNull(signal.rightTurn) // Aggregate active plus an off-half-cycle is unknown, not cleared.
        assertEquals(0f, requireNotNull(signal.steeringLeftDegrees), 0f)
        assertEquals(12.5f, requireNotNull(signal.steeringRightDegrees), 0f)
        val offOverridesTransientSideValue = CameraVehicleSignalNormalizer.normalize(
            raw.copy(globalTurnState = 1), encoding,
        )
        assertEquals(false, offOverridesTransientSideValue.leftTurn)
        assertEquals(false, offOverridesTransientSideValue.rightTurn)
    }

    @Test fun steeringSignIsConfigurableAndOutOfDomainOrUncalibratedValuesStayUnknown() {
        val raw = CameraVehicleRawSignals(4, 0, null, null, null, -10.0)
        val normal = CameraVehicleSignalNormalizer.normalize(raw, tang)
        assertEquals(10f, requireNotNull(normal.steeringLeftDegrees), 0f)
        assertEquals(0f, requireNotNull(normal.steeringRightDegrees), 0f)
        val reversed = CameraVehicleSignalNormalizer.normalize(raw, tang.copy(positiveSteeringMeansRight = false))
        assertEquals(0f, requireNotNull(reversed.steeringLeftDegrees), 0f)
        assertEquals(10f, requireNotNull(reversed.steeringRightDegrees), 0f)
        val unknownScale = CameraVehicleSignalNormalizer.normalize(raw, tang.copy(steeringDegreesPerRawUnit = null))
        assertNull(unknownScale.steeringLeftDegrees)
        assertNull(CameraVehicleSignalNormalizer.normalize(raw.copy(steeringValue = 900.0), tang).steeringRightDegrees)
    }

    @Test fun workerProtocolOnlyAcceptsTokenBoundReadOperations() {
        val token = "0123456789abcdef0123456789abcdef"
        assertEquals("read", CameraVehicleSignalProtocol.parse("$token read", token))
        assertEquals("ping", CameraVehicleSignalProtocol.parse("$token ping", token))
        assertEquals("close", CameraVehicleSignalProtocol.parse("$token close", token))
        assertNull(CameraVehicleSignalProtocol.parse("$token write 1 2", token))
        assertNull(CameraVehicleSignalProtocol.parse("$token read extra", token))
        assertNull(CameraVehicleSignalProtocol.parse("00000000000000000000000000000000 read", token))
        assertNull(CameraVehicleSignalProtocol.parse("$token " + "x".repeat(300), token))
    }
}
