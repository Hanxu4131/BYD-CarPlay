package com.shilapi.xcertplay.camera

import kotlin.math.PI
import org.junit.Assert.*
import org.junit.Test

class CameraProjectionMathTest {
    private val lens = CameraFisheyeLens(.5f, .5f, .5f, .5f, PI.toFloat(), CameraCrop())
    private val base = CameraProjection.EquidistantFisheye(lens, horizontalFovRadians = (PI / 2).toFloat(), outputAspect = 1f)
    private fun sample(u: Float, v: Float, p: CameraProjection = base) = requireNotNull(CameraProjectionMath.project(u, v, 512, 512, p))

    @Test fun noProjectionPreservesAlreadyProcessedUvExactly() {
        assertEquals(CameraProjectedUv(.13f, .71f), sample(.13f, .71f, CameraProjection.None))
        assertNull(CameraProjectionMath.project(-.1f, .5f, 512, 512, CameraProjection.None))
    }

    @Test fun identityRotationMapsOpticalAxisAndSymmetricPerspectiveRays() {
        assertEquals(CameraProjectedUv(.5f, .5f), sample(.5f, .5f))
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), CameraProjectionMath.rotation(base), .00001f)
        assertEquals(.75f, sample(1f, .5f).u, .00001f)
        assertEquals(.25f, sample(0f, .5f).u, .00001f)
        assertEquals(.25f, sample(.5f, 0f).v, .00001f)
        assertEquals(.75f, sample(.5f, 1f).v, .00001f)
    }

    @Test fun positiveYawLooksRightAndPositivePitchLooksUp() {
        val yaw = sample(.5f, .5f, base.copy(yawRadians = (PI / 4).toFloat()))
        val pitch = sample(.5f, .5f, base.copy(pitchRadians = (PI / 4).toFloat()))
        assertEquals(.75f, yaw.u, .00001f); assertEquals(.5f, yaw.v, .00001f)
        assertEquals(.5f, pitch.u, .00001f); assertEquals(.25f, pitch.v, .00001f)
        assertEquals(.25f, sample(.5f, .5f, base.copy(yawRadians = (-PI / 4).toFloat())).u, .00001f)
    }

    @Test fun positiveRollRotatesVisibleImageClockwiseWithTheDeclaredMatrixOrder() {
        val rolled = sample(1f, .5f, base.copy(rollRadians = (PI / 2).toFloat()))
        assertEquals(.5f, rolled.u, .00001f)
        assertEquals(.25f, rolled.v, .00001f)
        val together = base.copy(yawRadians = .2f, pitchRadians = .3f, rollRadians = .4f)
        val matrix = CameraProjectionMath.rotation(together)
        for (column in 0..2) {
            var norm = 0f
            for (row in 0..2) norm += matrix[column * 3 + row] * matrix[column * 3 + row]
            assertEquals(1f, norm, .00001f)
        }
        assertEquals(sample(.5f, .5f, together.copy(rollRadians = 0f)), sample(.5f, .5f, together))
        val corner = sample(1f, .5f, together)
        assertEquals(.79724664f, corner.u, .00001f)
        assertEquals(.31449195f, corner.v, .00001f)
    }

    @Test fun outsideFisheyeFovAndRearAxisAreBlackInsteadOfClampingIntoAnotherCamera() {
        assertNull(CameraProjectionMath.project(.5f, .5f, 512, 512, base.copy(yawRadians = (PI * .75).toFloat())))
        assertNull(CameraProjectionMath.project(.5f, .5f, 512, 512, base.copy(lens = lens.copy(fisheyeFovRadians = (2 * PI).toFloat()), yawRadians = PI.toFloat())))
    }

    @Test fun explicitLensTileAndChromaInsetRejectNeighborSampling() {
        val tile = CameraCrop(0f, 0f, .5f, 1f)
        val p = base.copy(lens = CameraFisheyeLens(.25f, .5f, .25f, .5f, PI.toFloat(), tile))
        assertEquals(CameraProjectedUv(.25f, .5f), sample(.5f, .5f, p))
        assertTrue(sample(.5f, .5f, p.copy(yawRadians = (PI / 3).toFloat())).u < .5f)
        assertNull(CameraProjectionMath.project(.5f, .5f, 1080, 360, p.copy(yawRadians = (PI / 2).toFloat())))
        assertFalse(p.copy(lens = p.lens.copy(centerX = .4f)).valid())
    }

    @Test fun independentLensRadiiAndOutputAspectDoNotStretchTheProjectedView() {
        val p = base.copy(lens = lens.copy(radiusX = .4f, radiusY = .2f), outputAspect = 2f)
        assertEquals(.7f, sample(1f, .5f, p).u, .00001f)
        assertTrue(sample(.5f, 0f, p).v > .4f)
        val geometry = requireNotNull(CameraRenderMath.coordinates(1080, 360, 512, 512, CameraViewTransform(), p.outputAspect))
        assertArrayEquals(floatArrayOf(-1f, -.5f, 1f, -.5f, -1f, .5f, 1f, .5f), geometry.vertices, .00001f)
    }

    @Test fun missingInvalidOrUnboundedCalibrationIsRejected() {
        assertFalse(base.copy(lens = lens.copy(radiusX = 0f)).valid())
        assertFalse(base.copy(lens = lens.copy(centerY = Float.NaN)).valid())
        assertFalse(base.copy(lens = lens.copy(fisheyeFovRadians = 0f)).valid())
        assertFalse(base.copy(horizontalFovRadians = PI.toFloat()).valid())
        assertFalse(base.copy(outputAspect = 0f).valid())
        assertFalse(base.copy(outputAspect = 1e-30f).valid())
        assertFalse(base.copy(yawRadians = Float.POSITIVE_INFINITY).valid())
        assertNull(CameraProjectionMath.project(.5f, .5f, 0, 512, base))
    }
    @Test fun rectilinearIdentityUsesExplicitSourceFovAndRoiPixelAspect() {
        val p = CameraProjection.Rectilinear(CameraCrop(0f, .25f, 1f, .75f),
            cameraSourceHorizontalFovRadians = (PI / 2).toFloat(),
            horizontalFovRadians = (PI / 2).toFloat(), outputAspect = 2f)
        assertArrayEquals(floatArrayOf(1f, .5f), requireNotNull(CameraProjectionMath.sourceTangents(p, 512, 512)), .00001f)
        val uv = sample(.25f, .25f, p)
        assertEquals(.25f, uv.u, .00001f)
        assertEquals(.375f, uv.v, .00001f)
        assertEquals(CameraProjectedUv(.5f, .5f), sample(.5f, .5f, p))
    }

    @Test fun rectilinearRotationsHaveTheSameDirectionButRejectBackwardOrOutsideSourceRays() {
        val p = CameraProjection.Rectilinear(CameraCrop(),
            cameraSourceHorizontalFovRadians = (PI / 2).toFloat(),
            horizontalFovRadians = (PI / 2).toFloat(), outputAspect = 1f)
        val yaw = sample(.5f, .5f, p.copy(yawRadians = (PI / 8).toFloat()))
        val pitch = sample(.5f, .5f, p.copy(pitchRadians = (PI / 8).toFloat()))
        assertEquals(.70710677f, yaw.u, .00001f)
        assertEquals(.29289323f, pitch.v, .00001f)
        val roll = sample(.75f, .5f, p.copy(rollRadians = (PI / 2).toFloat()))
        assertEquals(.5f, roll.u, .00001f); assertEquals(.25f, roll.v, .00001f)
        assertNull(CameraProjectionMath.project(.5f, .5f, 512, 512, p.copy(yawRadians = PI.toFloat())))
        assertNull(CameraProjectionMath.project(.5f, .5f, 512, 512, p.copy(yawRadians = (PI / 3).toFloat())))
    }

    @Test fun rectilinearNeverGuessesMissingOrInvalidSourceCalibration() {
        val p = CameraProjection.Rectilinear(CameraCrop(0f, 0f, .5f, 1f),
            cameraSourceHorizontalFovRadians = (PI / 2).toFloat(),
            horizontalFovRadians = (PI / 2).toFloat(), outputAspect = 1f)
        assertFalse(p.copy(cameraSourceHorizontalFovRadians = 0f).valid())
        assertFalse(p.copy(cameraSourceHorizontalFovRadians = Float.NaN).valid())
        assertFalse(p.copy(sourceRegion = CameraCrop(.5f, 0f, .1f, 1f)).valid())
        assertNull(CameraProjectionMath.project(1f, .5f, 1080, 360, p))
        assertEquals(CameraProjectedUv(.25f, .5f), sample(.5f, .5f, p))
    }

}
