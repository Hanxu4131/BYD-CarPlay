package com.shilapi.xcertplay.camera

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CameraSettingsTest {
    @Test fun disabledByDefaultAndViewportDefaultsMatchTheTargetScreens() {
        val defaults = CameraSettings.Values()
        assertFalse(defaults.enabled)
        val rear = defaults.views.getValue(CameraView.REAR).viewport
        assertEquals(0.28f, rear.x, 1e-6f)
        assertEquals(0.20f, rear.y, 1e-6f)
        assertEquals(0.44f, rear.width, 1e-6f)
        assertEquals((0.44f * 1920f / 3f) / 720f, rear.height, 1e-6f)

        val leftRear = defaults.views.getValue(CameraView.LEFT_REAR).viewport
        val rightRear = defaults.views.getValue(CameraView.RIGHT_REAR).viewport
        assertEquals(CameraViewport(0.02f, 0.18f, 0.25f, 0.56f), leftRear)
        assertEquals(CameraViewport(0.73f, 0.18f, 0.25f, 0.56f), rightRear)

        val left = defaults.views.getValue(CameraView.LEFT_FRONT).viewport
        val right = defaults.views.getValue(CameraView.RIGHT_FRONT).viewport
        assertEquals(left, right) // overlapping lower-card windows use policy z-order
        assertEquals(22f / 1920f, left.x, 1e-6f)
        assertEquals(748f / 1080f, left.y, 1e-6f)
        assertEquals(592f / 1920f, left.width, 1e-6f)
        assertEquals(242f / 1080f, left.height, 1e-6f)
        assertEquals(990f / 1080f, left.y + left.height, 1e-6f)
        val frontPx = left.toPixelRect(1920, 1080)
        assertEquals(CameraPixelRect(22, 748, 592, 242), frontPx)
        assertTrue(frontPx.top >= 739) // below the card action icon
        assertTrue(frontPx.top + frontPx.height <= 990) // above the bottom menu bar
    }

    @Test fun instrumentDefaultsMapToTheRequestedHostPixelRectangles() {
        val defaults = CameraSettings.Values()
        val left = defaults.views.getValue(CameraView.LEFT_REAR).viewport.toPixelRect(1920, 720)
        val right = defaults.views.getValue(CameraView.RIGHT_REAR).viewport.toPixelRect(1920, 720)
        val rear = defaults.views.getValue(CameraView.REAR).viewport.toPixelRect(1920, 720)
        assertEquals(CameraPixelRect(38, 130, 480, 403), left)
        assertEquals(CameraPixelRect(1402, 130, 480, 403), right)
        assertEquals(CameraPixelRect(538, 144, 845, 282), rear)
        assertTrue(left.left + left.width < rear.left)
        assertTrue(rear.left + rear.width < right.left)
    }

    @Test fun viewportPixelRectClampsToHostDisplay() {
        val rect = CameraViewport(0.9f, 0.9f, 0.5f, 0.5f).toPixelRect(640, 360)
        assertEquals(640, rect.left + rect.width)
        assertEquals(360, rect.top + rect.height)
    }

    @Test fun defaultFrontViewportKeepsPixelAspectAndAnchorsToCardAcrossDisplays() {
        listOf(1920 to 1080, 1280 to 720, 1080 to 1920).forEach { (width, height) ->
            val viewport = CameraSettings.defaultFrontViewport(width, height)
            assertEquals(22f / 1920f, viewport.x, 1e-6f)
            assertEquals(748f / 1080f, viewport.y, 1e-6f)
            assertEquals(592f / 1920f, viewport.width, 1e-6f)
            assertEquals(242f / 1080f, viewport.height, 1e-6f)
            assertEquals(990f / 1080f, viewport.y + viewport.height, 1e-6f)
            assertTrue(viewport.x >= 0f && viewport.x + viewport.width <= 1f)
            assertTrue(viewport.y >= 0f && viewport.y + viewport.height <= 1f)
        }
    }

    @Test fun defaultFrontViewportClampsToUsableDisplayBounds() {
        val viewport = CameraSettings.defaultFrontViewport(320, 240)
        assertTrue(viewport.x >= 0f && viewport.x + viewport.width <= 1f)
        assertTrue(viewport.y >= 0f && viewport.y + viewport.height <= 1f)
    }

    @Test fun opticsCalibrationIsPerViewAndFovIsInactiveUntilConfirmed() {
        val left = CameraSettings.Values().views.getValue(CameraView.LEFT_FRONT)
        assertEquals(0f, left.yawDegrees, 0f)
        assertEquals(0f, left.pitchDegrees, 0f)
        assertEquals(0f, left.rollDegrees, 0f)
        assertEquals(90f, left.fovDegrees, 0f)
        assertNull(left.effectiveFovDegrees)

        val confirmed = left.copy(
            yawDegrees = 12f,
            pitchDegrees = -4f,
            rollDegrees = 2f,
            fovDegrees = 104f,
            lens = CameraLensSettings(
                projectionMode = CameraProjectionMode.FISHEYE_EQUIDISTANT,
                lensCenterX = 0.47f,
                lensCenterY = 0.53f,
                lensRadiusX = 0.44f,
                lensRadiusY = 0.46f,
                sourceRect = CameraSourceCrop(0.05f, 0.1f, 0.95f, 0.9f),
                sourceCameraTag = "front-left",
                sourceId = "camera-a",
                calibrationConfirmed = true,
            ),
        ).sanitized()
        assertEquals(104f, confirmed.effectiveFovDegrees!!, 0f)
        val allViews = CameraSettings.defaults() + (CameraView.LEFT_FRONT to confirmed)
        assertEquals(CameraLensSettings(), allViews.getValue(CameraView.RIGHT_FRONT).lens)
    }

    @Test fun opticsValuesAreClampedAndNonFiniteValuesFallBackSafely() {
        val base = CameraSettings.Values().views.getValue(CameraView.REAR)
        val safe = base.copy(
            yawDegrees = Float.POSITIVE_INFINITY,
            pitchDegrees = -100f,
            rollDegrees = 500f,
            fovDegrees = 0f,
            lens = CameraLensSettings(
                lensCenterX = Float.NaN,
                lensCenterY = 2f,
                lensRadiusX = -1f,
                lensRadiusY = 4f,
                sourceCameraTag = "  ",
                calibrationConfirmed = true,
            ),
        ).sanitized()
        assertEquals(0f, safe.yawDegrees, 0f)
        assertEquals(-90f, safe.pitchDegrees, 0f)
        assertEquals(180f, safe.rollDegrees, 0f)
        assertEquals(10f, safe.fovDegrees, 0f)
        assertNull(safe.lens.lensCenterX)
        assertEquals(1f, safe.lens.lensCenterY!!, 0f)
        assertEquals(0.01f, safe.lens.lensRadiusX!!, 0f)
        assertEquals(1f, safe.lens.lensRadiusY!!, 0f)
        assertNull(safe.lens.sourceCameraTag)
        assertEquals(10f, safe.effectiveFovDegrees!!, 0f)
    }

    @Test fun everyViewHasIndependentTransformMirrorAndViewportSettings() {
        val original = CameraSettings.Values()
        val changed = original.views.toMutableMap()
        changed[CameraView.LEFT_FRONT] = changed.getValue(CameraView.LEFT_FRONT).copy(
            crop = CameraSourceCrop(0.1f, 0.2f, 0.8f, 0.9f), panX = 0.25f,
            zoom = 2f, mirrored = true, viewport = CameraViewport(0.2f, 0.3f, 0.4f, 0.3f),
        )
        val settings = original.copy(views = changed).sanitized()
        assertEquals(CameraSourceCrop(0.1f, 0.2f, 0.8f, 0.9f), settings.views.getValue(CameraView.LEFT_FRONT).crop)
        assertFalse(settings.views.getValue(CameraView.RIGHT_FRONT).mirrored)
        assertEquals(original.views.getValue(CameraView.RIGHT_FRONT).viewport,
            settings.views.getValue(CameraView.RIGHT_FRONT).viewport)
    }

    @Test fun malformedGeometryIsClampedToUsableFractions() {
        val source = CameraSettings.Values(views = CameraSettings.defaults() + (CameraView.REAR to
            CameraViewSettings(
                crop = CameraSourceCrop(Float.NaN, -0.1f, 0f, 4f), panX = 9f, panY = -9f,
                zoom = Float.NaN, mirrored = true,
                viewport = CameraViewport(2f, -1f, 0f, 3f),
            )))
        val safe = source.sanitized().views.getValue(CameraView.REAR)
        assertEquals(0f, safe.crop.left, 0f)
        assertTrue(safe.crop.right > safe.crop.left)
        assertTrue(safe.crop.bottom <= 1f)
        assertEquals(1f, safe.zoom, 0f)
        assertEquals(1f, safe.panX, 0f)
        assertEquals(-1f, safe.panY, 0f)
        assertTrue(safe.viewport.x + safe.viewport.width <= 1f + 1e-6f)
        assertTrue(safe.viewport.y + safe.viewport.height <= 1f + 1e-6f)
    }

    @Test fun saveCancelBoundaryAndPerViewFieldsRoundTripThroughPreferences() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("diplay_camera_views", 0)
        prefs.edit().clear().commit()
        try {
            val defaults = CameraSettings.load(context)
            assertFalse(defaults.enabled)
            val draftViews = defaults.views.toMutableMap()
            draftViews[CameraView.RIGHT_REAR] = draftViews.getValue(CameraView.RIGHT_REAR).copy(
                mirrored = true, zoom = 1.75f,
                yawDegrees = -18f,
                fovDegrees = 112f,
                lens = CameraLensSettings(
                    projectionMode = CameraProjectionMode.RECTILINEAR,
                    lensCenterX = 0.42f,
                    sourceRect = CameraSourceCrop(0.1f, 0.05f, 0.9f, 0.95f),
                    sourceId = "rear-right-source",
                    calibrationConfirmed = true,
                ),
            )
            val draft = defaults.copy(enabled = true, views = draftViews)
            // A canceled UI draft never calls save, so persisted state is untouched.
            assertFalse(CameraSettings.load(context).enabled)

            CameraSettings.save(context, draft)
            val loaded = CameraSettings.load(context)
            assertTrue(loaded.enabled)
            assertTrue(loaded.views.getValue(CameraView.RIGHT_REAR).mirrored)
            assertEquals(1.75f, loaded.views.getValue(CameraView.RIGHT_REAR).zoom, 1e-6f)
            val loadedOptics = loaded.views.getValue(CameraView.RIGHT_REAR)
            assertEquals(-18f, loadedOptics.yawDegrees, 0f)
            assertEquals(112f, loadedOptics.effectiveFovDegrees!!, 0f)
            assertEquals(CameraProjectionMode.RECTILINEAR, loadedOptics.lens.projectionMode)
            assertEquals(0.42f, loadedOptics.lens.lensCenterX!!, 0f)
            assertEquals("rear-right-source", loadedOptics.lens.sourceId)
            assertEquals(CameraSourceCrop(0.1f, 0.05f, 0.9f, 0.95f), loadedOptics.lens.sourceRect)
            assertFalse(loaded.views.getValue(CameraView.LEFT_REAR).mirrored)
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
