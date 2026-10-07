package com.shilapi.xcertplay.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraWindowOrderPlanTest {
    private val left = CameraView.LEFT_FRONT
    private val right = CameraView.RIGHT_FRONT
    @Test fun removingUpperPreservesLower() {
        assertEquals(CameraWindowOrderPlan(listOf(right), emptyList()), cameraWindowOrderPlan(listOf(left,right), listOf(left)))
    }
    @Test fun removingLowerPreservesUpper() {
        assertEquals(CameraWindowOrderPlan(listOf(left), emptyList()), cameraWindowOrderPlan(listOf(left,right), listOf(right)))
    }
    @Test fun addingUpperOnlyAddsNewWindow() {
        assertEquals(CameraWindowOrderPlan(emptyList(), listOf(right)), cameraWindowOrderPlan(listOf(left), listOf(left,right)))
    }
    @Test fun insertingMissingLowerRequiresReaddingUpper() {
        assertEquals(CameraWindowOrderPlan(listOf(right), listOf(left,right)), cameraWindowOrderPlan(listOf(right), listOf(left,right)))
    }
    @Test fun warmSingleVisibleNeverDetachesEitherSurface() {
        assertEquals(CameraWindowOrderPlan(emptyList(), emptyList()), cameraWarmWindowOrderPlan(listOf(left,right), listOf(left)))
        assertEquals(CameraWindowOrderPlan(emptyList(), emptyList()), cameraWarmWindowOrderPlan(listOf(left,right), listOf(right)))
    }
    @Test fun warmOrderConflictOnlyMovesNewUpper() {
        assertEquals(CameraWindowOrderPlan(listOf(left), listOf(left)), cameraWarmWindowOrderPlan(listOf(left,right), listOf(right,left)))
        assertEquals(CameraWindowOrderPlan(emptyList(), emptyList()), cameraWarmWindowOrderPlan(listOf(left,right), listOf(left,right)))
    }
    @Test fun warmMissingBottomRequiresMovingExistingUpper() {
        assertEquals(CameraWindowOrderPlan(listOf(right), listOf(left,right)), cameraWarmWindowOrderPlan(listOf(right), listOf(left,right)))
    }
    @Test fun firstFrameAttachesWarmWithoutShowingBeforeTrigger() {
        assertEquals(CameraWindowPresentation(false,false), cameraWindowPresentation(false,false,false,false))
        assertEquals(CameraWindowPresentation(true,false), cameraWindowPresentation(false,true,true,false))
        assertEquals(CameraWindowPresentation(true,true), cameraWindowPresentation(true,true,true,true))
    }
    @Test fun hiddenAndExpiredWindowsRemainAttached() {
        assertEquals(CameraWindowPresentation(true,false), cameraWindowPresentation(true,true,true,false))
        assertEquals(CameraWindowPresentation(true,false), cameraWindowPresentation(true,true,false,true))
    }
}
