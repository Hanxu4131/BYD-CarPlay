package com.shilapi.xcertplay.camera

import org.junit.Assert.*
import org.junit.Test

class CameraVisibilityPolicyTest {
    private fun settings(enabled: Boolean = true) = CameraSettings.Values(enabled = enabled)
    private fun signals(
        gearReverse: Boolean? = false,
        gearDrive: Boolean? = true,
        split: Boolean? = true,
        brake: Float? = 0f,
        leftTurn: Boolean? = false,
        rightTurn: Boolean? = false,
        leftDegrees: Float? = 0f,
        rightDegrees: Float? = 0f,
    ) = CameraVehicleSignals(gearReverse, gearDrive, split, brake, leftTurn, rightTurn, leftDegrees, rightDegrees)

    @Test fun masterDefaultsOffAndDisablesEveryCameraWindow() {
        assertFalse(CameraSettings.Values().enabled)
        val policy = CameraVisibilityPolicy()
        assertTrue(policy.update(settings(false), signals(gearReverse = true, leftTurn = true)).visibleViews.isEmpty())
        val enabled = policy.update(settings(), signals())
        assertEquals(setOf(CameraView.LEFT_REAR, CameraView.RIGHT_REAR), enabled.visibleViews)
    }

    @Test fun reverseOrDeepBrakeOpensRearInAnyGearAndRequiresBothSignalsToClear() {
        val policy = CameraVisibilityPolicy()
        assertTrue(CameraView.REAR !in policy.update(settings(), signals()).visibleViews)
        assertTrue(CameraView.REAR in policy.update(settings(), signals(gearReverse = true)).visibleViews)
        assertTrue(CameraView.REAR in policy.update(settings(), signals(gearReverse = false, brake = null)).visibleViews)
        assertTrue(CameraView.REAR in policy.update(settings(), signals(gearDrive = false, brake = 40f)).visibleViews)
        assertTrue(CameraView.REAR in policy.update(settings(), signals(gearReverse = null, brake = 39f)).visibleViews)
        assertTrue(CameraView.REAR !in policy.update(settings(), signals(gearReverse = false, brake = 39.9f)).visibleViews)
        assertTrue(CameraView.REAR in policy.update(settings(), signals(gearDrive = false, brake = 40f)).visibleViews)
    }

    @Test fun leftTurnThenRightAngleShowsBothAndNewestWindowOnTop() {
        val policy = CameraVisibilityPolicy()
        val left = policy.update(settings(), signals(leftTurn = true))
        assertEquals(listOf(CameraView.LEFT_FRONT), left.frontZOrder)
        val both = policy.update(settings(), signals(leftTurn = true, rightDegrees = 15.1f))
        assertEquals(listOf(CameraView.RIGHT_FRONT, CameraView.LEFT_FRONT), both.frontZOrder)
        assertTrue(CameraView.LEFT_FRONT in both.visibleViews)
        assertTrue(CameraView.RIGHT_FRONT in both.visibleViews)
    }

    @Test fun eachSideHidesOnlyAfterItsTurnAndSteeringTriggersBothClear() {
        val leftThenRight = CameraVisibilityPolicy()
        leftThenRight.update(settings(), signals(leftTurn = true))
        leftThenRight.update(settings(), signals(leftTurn = true, rightDegrees = 20f))
        val rightOnly = leftThenRight.update(settings(), signals(leftTurn = false, leftDegrees = 0f,
            rightTurn = true, rightDegrees = 20f))
        assertEquals(listOf(CameraView.RIGHT_FRONT), rightOnly.frontZOrder)

        val rightThenLeft = CameraVisibilityPolicy()
        rightThenLeft.update(settings(), signals(leftTurn = true))
        rightThenLeft.update(settings(), signals(leftTurn = true, rightDegrees = 20f))
        val leftOnly = rightThenLeft.update(settings(), signals(leftTurn = true, rightTurn = false,
            rightDegrees = 0f))
        assertEquals(listOf(CameraView.LEFT_FRONT), leftOnly.frontZOrder)
    }

    @Test fun simultaneousActivationUsesStableLeftOnTopAndSamplesDoNotStealZOrder() {
        val policy = CameraVisibilityPolicy()
        val both = policy.update(settings(), signals(leftTurn = true, rightTurn = true))
        assertEquals(listOf(CameraView.LEFT_FRONT, CameraView.RIGHT_FRONT), both.frontZOrder)
        val stillBoth = policy.update(settings(), signals(leftTurn = true, rightTurn = true,
            leftDegrees = 50f, rightDegrees = 90f))
        assertEquals(both.frontZOrder, stillBoth.frontZOrder)
    }

    @Test fun staleSignalsPreserveExistingWindowsButCannotOpenOrReorderThem() {
        val policy = CameraVisibilityPolicy()
        policy.update(settings(), signals(leftTurn = true))
        val both = policy.update(settings(), signals(leftTurn = true, rightDegrees = 25f))
        val staleGateAndLeft = policy.update(settings(), signals(gearDrive = null,
            leftTurn = null, leftDegrees = null, rightDegrees = 30f, rightTurn = true))
        assertEquals(both.frontZOrder, staleGateAndLeft.frontZOrder)

        val staleTriggers = policy.update(settings(), signals(leftTurn = null, leftDegrees = null,
            rightTurn = null, rightDegrees = null))
        assertEquals(both.frontZOrder, staleTriggers.frontZOrder)
    }

    @Test fun explicitFalseDriveOrSplitGateClosesFrontWindows() {
        val policy = CameraVisibilityPolicy()
        policy.update(settings(), signals(leftTurn = true, rightTurn = true))
        assertTrue(policy.update(settings(), signals(gearDrive = false, leftTurn = true, rightTurn = true))
            .frontZOrder.isEmpty())
        policy.update(settings(), signals(leftTurn = true))
        assertTrue(policy.update(settings(), signals(split = false, leftTurn = true)).frontZOrder.isEmpty())
    }

    @Test fun adapterRangesAreValidatedBeforePolicyEvaluation() {
        val policy = CameraVisibilityPolicy()
        assertThrows(IllegalArgumentException::class.java) {
            policy.update(settings(), signals(brake = 101f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.update(settings(), signals(leftDegrees = -1f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.update(settings(), signals(rightDegrees = Float.NaN))
        }
    }
}
