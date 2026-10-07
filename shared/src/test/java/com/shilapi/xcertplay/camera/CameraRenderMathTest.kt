package com.shilapi.xcertplay.camera

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class CameraRenderMathTest {
    private val normal = CameraViewTransform()
    private fun coordinates(w: Int, h: Int, vw: Int, vh: Int, transform: CameraViewTransform = normal) =
        requireNotNull(CameraRenderMath.coordinates(w, h, vw, vh, transform))

    @Test fun squareAndWideConfirmedFramesFitWithoutStretching() {
        assertArrayEquals(floatArrayOf(-.5f, -1f, .5f, -1f, -.5f, 1f, .5f, 1f), coordinates(512, 512, 1080, 540).vertices, .00001f)
        assertArrayEquals(floatArrayOf(-1f, -.5f, 1f, -.5f, -1f, .5f, 1f, .5f), coordinates(1080, 360, 900, 600).vertices, .00001f)
        assertArrayEquals(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f), coordinates(512, 512, 512, 512).texture, .00001f)
    }

    @Test fun cropUsesItsOwnAspectAndZoomNeverChangesThatAspect() {
        val crop = CameraCrop(.25f, 0f, .75f, 1f)
        val a = coordinates(1080, 360, 900, 600, normal.copy(crop = crop))
        val b = coordinates(1080, 360, 900, 600, normal.copy(crop = crop, scale = 2f))
        assertArrayEquals(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f), a.vertices, .00001f)
        assertArrayEquals(a.vertices, b.vertices, .00001f)
        assertArrayEquals(floatArrayOf(.375f, .75f, .625f, .75f, .375f, .25f, .625f, .25f), b.texture, .00001f)
    }

    @Test fun panExtremesStayWithinSourceAndMirrorOnlyChangesHorizontalSampling() {
        for (panX in listOf(-1f, 0f, 1f)) for (panY in listOf(-1f, 0f, 1f)) {
            val t = normal.copy(scale = 2f, panX = panX, panY = panY)
            val a = coordinates(512, 512, 1500, 500, t)
            val b = coordinates(512, 512, 1500, 500, t.copy(mirrorX = true))
            assertTrue(a.texture.all { it in 0f..1f })
            assertArrayEquals(a.vertices, b.vertices, 0f)
            assertEquals(a.texture[0], b.texture[2], 0f)
            assertEquals(a.texture[2], b.texture[0], 0f)
            assertEquals(a.texture[1], b.texture[1], 0f)
        }
        assertEquals(.5f, coordinates(512, 512, 512, 512, normal.copy(scale = 2f, panX = 1f)).texture[0], .00001f)
    }

    @Test fun scalingDownShrinksThePictureWithoutChangingTheCropOrRatio() {
        val a = coordinates(1080, 360, 600, 600)
        val b = coordinates(1080, 360, 600, 600, normal.copy(scale = .5f))
        assertArrayEquals(a.texture, b.texture, 0f)
        assertArrayEquals(a.vertices.map { it * .5f }.toFloatArray(), b.vertices, .00001f)
    }

    @Test fun dimensionsAndPayloadMustMatchTheExplicitEvenSizeAndStrides() {
        val square = CameraFrameLayout(512, 512, 512, 512, CameraPixelFormat.NV12)
        val wide = CameraFrameLayout(1080, 360, 1080, 1080, CameraPixelFormat.NV21)
        assertEquals(393216, square.payloadBytes())
        assertEquals(583200, wide.payloadBytes())
        assertTrue(square.accepts(393216))
        assertFalse(square.accepts(393215))
        assertNull(square.copy(width = 511).payloadBytes())
        assertNull(square.copy(height = 0).payloadBytes())
        assertNull(square.copy(yRowStride = 500).payloadBytes())
        assertNull(CameraFrameLayout(Int.MAX_VALUE - 1, Int.MAX_VALUE - 1, Int.MAX_VALUE - 1, Int.MAX_VALUE - 1, CameraPixelFormat.NV12).payloadBytes())
    }

    @Test fun rowPaddingIsRemovedWithoutChangingAnyYOrChromaByteOrder() {
        val layout = CameraFrameLayout(4, 2, 6, 6, CameraPixelFormat.NV21)
        val source = byteArrayOf(1, 2, 3, 4, 99, 99, 5, 6, 7, 8, 99, 99, 20, 10, 40, 30, 99, 99)
        val target = ByteBuffer.allocateDirect(12)
        layout.packInto(source, target)
        val actual = ByteArray(target.remaining()); target.get(actual)
        assertEquals(18, layout.payloadBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 20, 10, 40, 30), actual)
    }

    @Test fun syntheticRedAndBlueBlocksProveExplicitNv12VersusNv21Order() {
        val redNv12 = CameraRenderMath.referenceRgb(81, 90, 240, CameraPixelFormat.NV12, CameraYuvRange.LIMITED)
        val redNv21 = CameraRenderMath.referenceRgb(81, 240, 90, CameraPixelFormat.NV21, CameraYuvRange.LIMITED)
        assertArrayEquals(redNv12, redNv21, .000001f)
        assertTrue(redNv12[0] > .98f && redNv12[1] < .02f && redNv12[2] < .02f)
        val blueNv12 = CameraRenderMath.referenceRgb(41, 240, 110, CameraPixelFormat.NV12, CameraYuvRange.LIMITED)
        val blueNv21 = CameraRenderMath.referenceRgb(41, 110, 240, CameraPixelFormat.NV21, CameraYuvRange.LIMITED)
        assertArrayEquals(blueNv12, blueNv21, .000001f)
        assertTrue(blueNv12[2] > .98f && blueNv12[0] < .02f && blueNv12[1] < .02f)
        assertEquals(0f, CameraRenderMath.swapUv(CameraPixelFormat.NV12), 0f)
        assertEquals(1f, CameraRenderMath.swapUv(CameraPixelFormat.NV21), 0f)
    }

    @Test fun limitedAndFullNeutralBlackAndWhiteMatchTheirExplicitRanges() {
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), CameraRenderMath.referenceRgb(16, 128, 128, CameraPixelFormat.NV12, CameraYuvRange.LIMITED), .00001f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), CameraRenderMath.referenceRgb(235, 128, 128, CameraPixelFormat.NV21, CameraYuvRange.LIMITED), .0001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), CameraRenderMath.referenceRgb(0, 128, 128, CameraPixelFormat.NV21, CameraYuvRange.FULL), .00001f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), CameraRenderMath.referenceRgb(255, 128, 128, CameraPixelFormat.NV12, CameraYuvRange.FULL), .00001f)
    }

    @Test fun invalidCropAndTransformsAreRejectedRatherThanClampedSilently() {
        assertFalse(normal.copy(crop = CameraCrop(.8f, 0f, .2f, 1f)).valid())
        assertFalse(normal.copy(panX = 2f).valid())
        assertFalse(normal.copy(scale = Float.NaN).valid())
        assertFalse(normal.copy(scale = 0f).valid())
        assertNull(CameraRenderMath.coordinates(512, 512, 0, 600, normal))
    }
}
