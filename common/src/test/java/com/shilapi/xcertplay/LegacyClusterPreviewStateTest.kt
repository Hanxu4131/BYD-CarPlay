package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class LegacyClusterPreviewStateTest {
    @Test fun aLateDismissCannotClearTheNewEditorsReferenceFrame() {
        val state = LegacyClusterPreviewState()
        val whole = state.beginLayout(LegacyClusterLayout.Settings(widthPercent = 65))
        val key = state.beginKeyArea(LegacyClusterKeyArea.Settings())
        assertNull(state.layout)
        assertFalse(state.end(whole))
        assertFalse(state.updateLayout(whole, LegacyClusterLayout.Settings(widthPercent = 100)))
        assertTrue(state.owns(key, LegacyClusterPreviewState.Kind.KEY_AREA))
        assertEquals(LegacyClusterKeyArea.Settings(), state.keyArea)
    }
    @Test fun cancellationClearsTheOverrideWithoutChangingTheBaselineValue() {
        val baseline = LegacyClusterLayout.Settings(50, 50, 65)
        val state = LegacyClusterPreviewState()
        val id = state.beginLayout(baseline)
        assertTrue(state.updateLayout(id, baseline.copy(widthPercent = 80)))
        assertEquals(80, state.layout?.widthPercent)
        assertTrue(state.end(id))
        assertNull(state.layout)
        assertEquals(65, baseline.widthPercent)
        assertFalse(state.owns(id, LegacyClusterPreviewState.Kind.LAYOUT))
    }
    @Test fun disconnectInvalidatesUpdatesAndCommitOwnership() {
        val state = LegacyClusterPreviewState()
        val old = state.beginKeyArea(LegacyClusterKeyArea.Settings())
        state.clearAll()
        assertFalse(state.owns(old, LegacyClusterPreviewState.Kind.KEY_AREA))
        assertFalse(state.updateKeyArea(old, LegacyClusterKeyArea.Settings(scalePercent = 165)))
        val newer = state.beginKeyArea(LegacyClusterKeyArea.Settings(scalePercent = 105))
        assertFalse(state.end(old))
        assertTrue(state.owns(newer, LegacyClusterPreviewState.Kind.KEY_AREA))
        assertEquals(105, state.keyArea?.scalePercent)
    }
    @Test fun anEditorCannotUpdateOrCommitTheOtherKind() {
        val state = LegacyClusterPreviewState()
        val id = state.beginLayout(LegacyClusterLayout.Settings())
        assertFalse(state.updateKeyArea(id, LegacyClusterKeyArea.Settings()))
        assertFalse(state.owns(id, LegacyClusterPreviewState.Kind.KEY_AREA))
        assertNull(state.keyArea)
    }
}
