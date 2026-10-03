package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class LegacyClusterLayoutTest {
    @Test fun defaultMapIsHalfWidthAndCenteredOnProjectionCanvas() {
        assertEquals(LegacyClusterLayout.Plan(480, 180, 960, 360), LegacyClusterLayout.plan(1920, 720))
    }
    @Test fun positionUsesOnlyRemainingSpaceWithoutClippingMap() {
        assertEquals(LegacyClusterLayout.Plan(0, 0, 960, 360),
            LegacyClusterLayout.plan(1920, 720, LegacyClusterLayout.Settings(0, 0, 50)))
        assertEquals(LegacyClusterLayout.Plan(960, 360, 960, 360),
            LegacyClusterLayout.plan(1920, 720, LegacyClusterLayout.Settings(100, 100, 50)))
        assertEquals(LegacyClusterLayout.Plan(528, 198, 960, 360),
            LegacyClusterLayout.plan(1920, 720, LegacyClusterLayout.Settings(55, 55, 50)))
    }
    @Test fun percentagesAreBoundedAndUseFivePercentSteps() {
        assertEquals(LegacyClusterLayout.Settings(0, 100, 25),
            LegacyClusterLayout.sanitize(LegacyClusterLayout.Settings(-30, 200, 0)))
        assertEquals(LegacyClusterLayout.Settings(50, 55, 100),
            LegacyClusterLayout.sanitize(LegacyClusterLayout.Settings(51, 53, 200)))
        assertEquals(LegacyClusterLayout.Plan(720, 270, 480, 180),
            LegacyClusterLayout.plan(1920, 720, LegacyClusterLayout.Settings(widthPercent = 25)))
    }
    @Test fun maxWidthFitsProjectionCanvasWhileKeepingTheFullMapAspect() {
        assertEquals(LegacyClusterLayout.Plan(0, 0, 1920, 720),
            LegacyClusterLayout.plan(1920, 720, LegacyClusterLayout.Settings(widthPercent = 100)))
        for ((width, height) in listOf(1920 to 200, 720 to 1920, 1280 to 800, 1919 to 719)) {
            val plan = LegacyClusterLayout.plan(width, height, LegacyClusterLayout.Settings(100, 100, 100))!!
            assertTrue(plan.left >= 0 && plan.top >= 0)
            assertTrue(plan.left + plan.width <= width && plan.top + plan.height <= height)
            assertEquals(8.0 / 3, plan.width.toDouble() / plan.height, 0.01)
        }
    }
    @Test fun anUnlaidOutWindowCannotProduceAPlan() {
        assertNull(LegacyClusterLayout.plan(0, 720))
        assertNull(LegacyClusterLayout.plan(1920, 0))
        assertNull(LegacyClusterLayout.plan(-1, -1))
    }
}
