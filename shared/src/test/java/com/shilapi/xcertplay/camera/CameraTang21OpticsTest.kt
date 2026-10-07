package com.shilapi.xcertplay.camera

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CameraTang21OpticsTest {
    @Test fun upgradePreservesUserWindowAndAdjustmentsWhileEnablingRawProjection() {
        val values = CameraSettings.Values(enabled = true)
        val view = CameraView.LEFT_REAR
        val custom = values.views.getValue(view).copy(
            yawDegrees = 22f, pitchDegrees = -8f, zoom = 1.6f,
            viewport = CameraViewport(.04f, .24f, .23f, .4f),
        )
        val next = CameraTang21Optics.bootstrap(values.copy(views = values.views + (view to custom)))
        val result = next.views.getValue(view)
        assertTrue(CameraIntegration.canProject(result, 1.2f))
        assertEquals(custom.viewport, result.viewport)
        assertEquals(custom.yawDegrees, result.yawDegrees)
        assertEquals(custom.pitchDegrees, result.pitchDegrees)
        assertEquals(custom.zoom, result.zoom)
        assertTrue(next.enabled)
    }
    @Test fun validCustomLensIsNeverReplacedAndDefaultMasterStaysOff() {
        val first = CameraTang21Optics.bootstrap(CameraSettings.Values())
        val view = CameraView.RIGHT_REAR
        val custom = first.views.getValue(view).copy(lens = first.views.getValue(view).lens.copy(
            lensCenterX = .46f, lensRadiusX = .43f, fisheyeFovDegrees = 192f,
        ))
        val again = CameraTang21Optics.bootstrap(first.copy(views = first.views + (view to custom)))
        assertEquals(custom, again.views.getValue(view))
        assertFalse(again.enabled)
    }
}
