package com.shilapi.xcertplay.camera

import org.junit.Assert.*
import org.junit.Test

class CameraSliderMappingTest {
    @Test fun everyThumbPositionMapsMonotonicallyAndRoundTripsAtDeclaredStep() {
        val mappings = listOf(CameraSliderMapping.YAW_ROLL, CameraSliderMapping.PITCH, CameraSliderMapping.FOV,
            CameraSliderMapping.PAN, CameraSliderMapping.ZOOM, CameraSliderMapping.POSITION, CameraSliderMapping.SIZE)
        mappings.forEach { mapping ->
            assertEquals(mapping.minimum, mapping.value(0), 0f)
            assertEquals(mapping.maximum, mapping.value(mapping.steps), 0f)
            var previous = mapping.minimum
            for (progress in 0..mapping.steps) {
                val value = mapping.value(progress)
                assertTrue(value >= previous)
                assertEquals(progress, mapping.progress(value))
                if (progress > 0) assertEquals(mapping.step, value - previous, .00003f)
                previous = value
            }
        }
    }
    @Test fun neutralAnglesAndPanHaveExactCentersAndZoomStartsAtOne() {
        assertEquals(0f, CameraSliderMapping.YAW_ROLL.value(360), 0f)
        assertEquals(0f, CameraSliderMapping.PITCH.value(180), 0f)
        assertEquals(0f, CameraSliderMapping.PAN.value(100), .000001f)
        assertEquals(1f, CameraSliderMapping.ZOOM.value(0), 0f)
        assertEquals(8f, CameraSliderMapping.ZOOM.value(140), 0f)
    }
    @Test fun outOfRangeAndNonFiniteInputCannotProduceInvalidSliderProgress() {
        val mapping = CameraSliderMapping.FOV
        assertEquals(0, mapping.progress(Float.NaN))
        assertEquals(0, mapping.progress(-1f))
        assertEquals(mapping.steps, mapping.progress(500f))
        assertEquals(10f, mapping.value(-5), 0f)
        assertEquals(170f, mapping.value(Int.MAX_VALUE), 0f)
    }
    @Test fun windowSizeChangeConstrainsPositionAndReadbackShowsActualSavedValue() {
        val initial = CameraViewport(.7f, .6f, .25f, .25f)
        val changed = initial.copy(width = CameraSliderMapping.SIZE.value(CameraSliderMapping.SIZE.progress(.8f))).sanitized()
        assertEquals(.8f, changed.width, .00001f)
        assertEquals(.2f, changed.x, .00001f)
        assertEquals(.2f, CameraSliderMapping.POSITION.value(CameraSliderMapping.POSITION.progress(changed.x)), .00001f)
        assertEquals(initial.y, changed.y, 0f)
        assertEquals(initial.width, .25f, 0f) // Immutable original remains available for cancellation.
    }
    @Test fun independentlyAdjustedRearViewsDoNotChangeOtherViewsOrOriginal() {
        val original = CameraSettings.Values()
        val left = original.views.getValue(CameraView.LEFT_REAR)
        val draft = original.copy(views = original.views.toMutableMap().apply {
            put(CameraView.LEFT_REAR, left.copy(yawDegrees = CameraSliderMapping.YAW_ROLL.value(400),
                viewport = left.viewport.copy(width = .4f)).sanitized())
        })
        assertEquals(20f, draft.views.getValue(CameraView.LEFT_REAR).yawDegrees, 0f)
        assertEquals(original.views.getValue(CameraView.RIGHT_REAR), draft.views.getValue(CameraView.RIGHT_REAR))
        assertEquals(original.views.getValue(CameraView.REAR), draft.views.getValue(CameraView.REAR))
        assertEquals(left, original.views.getValue(CameraView.LEFT_REAR))
    }
}
