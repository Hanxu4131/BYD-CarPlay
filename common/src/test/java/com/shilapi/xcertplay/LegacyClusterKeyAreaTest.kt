package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class LegacyClusterKeyAreaTest {
    @Test fun defaultKeepsTheOriginalSafeRectangleAndPixelInsets() {
        assertEquals(LegacyClusterKeyArea.Rect(35.0, 16.0, 64.0, 75.0), LegacyClusterKeyArea.rect())
        assertEquals(LegacyClusterKeyArea.Insets(115, 180, 672, 691), LegacyClusterKeyArea.insets(1920, 720))
    }
    @Test fun fivePercentMovementTranslatesWithoutChangingSize() {
        val initial = LegacyClusterKeyArea.rect()
        val moved = LegacyClusterKeyArea.rect(LegacyClusterKeyArea.Settings(54.5, 50.5, 100))
        assertEquals(initial.left + 5, moved.left, 0.000001)
        assertEquals(initial.top + 5, moved.top, 0.000001)
        assertEquals(initial.right - initial.left, moved.right - moved.left, 0.000001)
        assertEquals(initial.bottom - initial.top, moved.bottom - moved.top, 0.000001)
    }
    @Test fun scalePreservesBothDimensionsProportionally() {
        val rect = LegacyClusterKeyArea.rect(LegacyClusterKeyArea.Settings(scalePercent = 105))
        assertEquals(29 * 1.05, rect.right - rect.left, 0.000001)
        assertEquals(59 * 1.05, rect.bottom - rect.top, 0.000001)
    }
    @Test fun extremePositionsAndScalesCannotLeaveOrInvertTheSafeRectangle() {
        for (scale in listOf(-100, 20, 100, 165, 999)) for (position in listOf(-100.0, 49.5, 200.0)) {
            val value = LegacyClusterKeyArea.Settings(position, position, scale)
            val rect = LegacyClusterKeyArea.rect(value)
            assertTrue(rect.left >= -0.000001 && rect.top >= -0.000001)
            assertTrue(rect.right <= 100.000001 && rect.bottom <= 100.000001)
            assertTrue(rect.left < rect.right && rect.top < rect.bottom)
            assertEquals(29.0 / 59, (rect.right - rect.left) / (rect.bottom - rect.top), 0.000001)
            val insets = LegacyClusterKeyArea.insets(1920, 720, value)
            assertTrue(insets.left >= 0 && insets.right >= 0 && insets.left + insets.right < 1920)
            assertTrue(insets.top >= 0 && insets.bottom >= 0 && insets.top + insets.bottom < 720)
        }
    }
    @Test fun invalidCentersAndSmallPixelCanvasesStayValid() {
        assertEquals(LegacyClusterKeyArea.Settings(), LegacyClusterKeyArea.sanitize(
            LegacyClusterKeyArea.Settings(Double.NaN, Double.POSITIVE_INFINITY)))
        assertEquals(LegacyClusterKeyArea.Insets(0, 0, 0, 0), LegacyClusterKeyArea.insets(1, 1))
        try { LegacyClusterKeyArea.insets(0, 720); fail("An empty canvas was accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun referenceFrameTracksTheActualMapPlacementAndItsOwnDraft() {
        val map = LegacyClusterLayout.Plan(480, 180, 960, 360)
        assertEquals(LegacyClusterLayout.Plan(816, 238, 278, 212),
            LegacyClusterKeyArea.project(map, LegacyClusterKeyArea.Settings()))
        assertEquals(LegacyClusterLayout.Plan(864, 256, 278, 212),
            LegacyClusterKeyArea.project(map, LegacyClusterKeyArea.Settings(54.5, 50.5)))
        val enlarged = LegacyClusterKeyArea.project(map, LegacyClusterKeyArea.Settings(scalePercent = 105))
        assertTrue(enlarged.width > 278 && enlarged.height > 212)
        assertTrue(enlarged.left >= map.left && enlarged.top >= map.top)
        assertTrue(enlarged.left + enlarged.width <= map.left + map.width)
        assertTrue(enlarged.top + enlarged.height <= map.top + map.height)
    }
}
