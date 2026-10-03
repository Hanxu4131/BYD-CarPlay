package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LegacyClusterTurnAreaTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val map = LegacyClusterLayout.Plan(480, 180, 960, 360)

    @Before fun clearPreferences() {
        context.getSharedPreferences("diplay_legacy_cluster_turn_area", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("diplay_legacy_cluster_safe_area", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun firstReadMigratesTheOldCardOnceAndLaterKeyChangesDoNotMoveIt() {
        val key = LegacyClusterKeyArea.Settings(54.5, 50.5, 105)
        LegacyClusterMap.setKeyArea(context, key)
        val initial = LegacyClusterMap.turnArea(context)
        val oldKey = LegacyClusterKeyArea.project(map, key)
        val card = LegacyClusterTurnArea.project(map, initial)
        assertTrue(kotlin.math.abs(oldKey.left - card.left) <= 1)
        assertTrue(kotlin.math.abs(oldKey.top - card.top) <= 1)
        assertTrue(kotlin.math.abs(oldKey.width - card.width) <= 1)
        assertTrue(kotlin.math.abs((oldKey.width * 0.36).toInt() - card.height) <= 1)
        LegacyClusterMap.setKeyArea(context, key.copy(horizontal = 70.0, vertical = 65.0))
        assertEquals(card, LegacyClusterTurnArea.project(map, LegacyClusterMap.turnArea(context)))
    }

    @Test fun savingTurnCoordinatesDoesNotChangeTheMapOrPhoneKeyArea() {
        val key = LegacyClusterMap.keyArea(context)
        val layout = LegacyClusterMap.layout(context)
        val baseline = LegacyClusterMap.turnArea(context)
        val moved = LegacyClusterTurnArea.move(baseline, 5, -5, 5)
        assertEquals(baseline.horizontal + 0.5, moved.horizontal, 0.000001)
        assertEquals(baseline.vertical - 0.5, moved.vertical, 0.000001)
        assertEquals(baseline.scalePercent + 5, moved.scalePercent)
        LegacyClusterMap.setTurnArea(context, moved)
        assertEquals(LegacyClusterTurnArea.project(map, moved),
            LegacyClusterTurnArea.project(map, LegacyClusterMap.turnArea(context)))
        assertEquals(key, LegacyClusterMap.keyArea(context))
        assertEquals(layout, LegacyClusterMap.layout(context))
        val movedKey = LegacyClusterKeyArea.move(key, -5, 5, 5)
        assertEquals(key.horizontal - 0.5, movedKey.horizontal, 0.000001)
        assertEquals(key.vertical + 0.5, movedKey.vertical, 0.000001)
        assertEquals(key.scalePercent + 5, movedKey.scalePercent)
    }

    @Test fun cancellationAndWindowLossInvalidateTurnPreviewWithoutSaving() {
        val baseline = LegacyClusterMap.turnArea(context)
        val id = LegacyClusterMap.beginTurnAreaPreview(baseline)
        LegacyClusterMap.previewTurnArea(id, baseline.copy(horizontal = 60.0))
        assertEquals(60.0, LegacyClusterMap.effectiveTurnArea(context).horizontal, 0.000001)
        LegacyClusterMap.endPreview(id)
        assertEquals(LegacyClusterTurnArea.project(map, baseline),
            LegacyClusterTurnArea.project(map, LegacyClusterMap.effectiveTurnArea(context)))
        assertFalse(LegacyClusterMap.commitTurnAreaPreview(context, id, baseline.copy(horizontal = 65.0)))
        val state = LegacyClusterPreviewState()
        val pending = state.beginTurnArea(baseline)
        state.clearAll()
        assertNull(state.turnArea)
        assertFalse(state.updateTurnArea(pending, baseline.copy(horizontal = 65.0)))
        assertFalse(state.owns(pending, LegacyClusterPreviewState.Kind.TURN_AREA))
    }

    @Test fun aLateKeyDialogDismissCannotClearTheNewTurnPreview() {
        val state = LegacyClusterPreviewState()
        val key = state.beginKeyArea(LegacyClusterKeyArea.Settings())
        val turn = state.beginTurnArea(LegacyClusterTurnArea.Settings())
        assertFalse(state.end(key))
        assertNull(state.keyArea)
        assertTrue(state.owns(turn, LegacyClusterPreviewState.Kind.TURN_AREA))
        assertFalse(state.updateKeyArea(turn, LegacyClusterKeyArea.Settings()))
        assertNotNull(state.turnArea)
    }

    @Test fun extremePositionsAndScalesKeepTheCardInsideItsMap() {
        for (scale in listOf(-100, 20, 100, 165, 999)) for (center in listOf(-100.0, 50.0, 200.0, Double.NaN)) {
            val card = LegacyClusterTurnArea.project(map, LegacyClusterTurnArea.Settings(center, center, scale))
            assertTrue(card.left >= map.left && card.top >= map.top)
            assertTrue(card.width > 0 && card.height > 0)
            assertTrue(card.left + card.width <= map.left + map.width)
            assertTrue(card.top + card.height <= map.top + map.height)
        }
    }
}
