package com.shilapi.xcertplay

import android.content.Intent
import android.view.KeyEvent
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class SteeringWheelMappingsTest {
    @Test fun draftCanBeCancelledBeforeOrAfterAKeyWithoutChangingSavedMapping() {
        val key = WheelKey(304, 10)
        val state = WheelMappingState(mapOf(WheelAction.SIRI to key))
        state.begin(WheelAction.SIRI)
        state.cancel()
        assertEquals(key, state.mappings[WheelAction.SIRI])
        state.begin(WheelAction.SIRI)
        state.capture(WheelKey(312, 11))
        state.cancel()
        assertEquals(key, state.mappings[WheelAction.SIRI])
    }
    @Test fun saveCommitsOneCaptureAndClearRestoresDefault() {
        val state = WheelMappingState()
        state.begin(WheelAction.NEXT)
        assertFalse(state.save())
        state.capture(WheelKey(87, 20))
        state.capture(WheelKey(88, 21))
        assertTrue(state.save())
        assertEquals(WheelAction.NEXT, state.action(WheelKey(87, 20)))
        state.clear(WheelAction.NEXT)
        assertNull(state.action(WheelKey(87, 20)))
    }
    @Test fun conflictsIncludeZeroScanFallbackButDifferentScansRemainDistinct() {
        val state = WheelMappingState(mapOf(WheelAction.PREVIOUS to WheelKey(304, 10)))
        state.begin(WheelAction.NEXT)
        state.capture(WheelKey(304, 0))
        assertEquals(WheelAction.PREVIOUS, state.conflict())
        assertFalse(state.save())
        state.cancel()
        state.begin(WheelAction.NEXT)
        state.capture(WheelKey(304, 11))
        assertTrue(state.save())
        assertEquals(WheelAction.NEXT, state.action(WheelKey(304, 11)))
        assertNull(state.action(WheelKey(304, 0)))
        assertNull(state.action(WheelKey(305, 10)))
    }
    @Test fun heldAndDuplicateInputPathsSendOnceButNextPressWorks() {
        val state = WheelMappingState()
        assertTrue(state.firstDown(WheelKey(87, 12), 100, 0))
        assertFalse(state.firstDown(WheelKey(87, 12), 100, 1))
        assertFalse(state.firstDown(WheelKey(87, 0), 100, 0))
        assertTrue(state.firstDown(WheelKey(87, 12), 200, 0))
    }
    @Test fun aFreshStoreReloadsSavedMappingsAndClearDoesNotLeaveOldKeys() {
        val context = RuntimeEnvironment.getApplication()
        val original = WheelMappingStore(context).load()
        try {
            val key = WheelKey(312, 41)
            WheelMappingStore(context).save(mapOf(WheelAction.SIRI to key))
            assertEquals(mapOf(WheelAction.SIRI to key), WheelMappingStore(context).load())
            WheelMappingStore(context).save(emptyMap())
            assertTrue(WheelMappingStore(context).load().isEmpty())
        } finally {
            WheelMappingStore(context).save(original)
        }
    }
    @Test fun mediaSessionRecordsTheKeyWithoutExecutingOldMediaActionAndPersistsSave() {
        val context = RuntimeEnvironment.getApplication()
        SteeringWheelMappings.clear(context, WheelAction.NEXT)
        val captures = mutableListOf<WheelKey>()
        val sends = mutableListOf<Int>()
        SteeringWheelMappings.begin(context, WheelAction.NEXT) { captures += it }
        val callback = CarPlayMediaCallback({ index, _ -> sends += index },
            { SteeringWheelMappings.consume(context, it, defaults = true) }, SteeringWheelMappings::capturing)
        val event = KeyEvent(123, 123, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0, 0, 2, 12)
        try {
            assertTrue(callback.onMediaButtonEvent(Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event)))
            callback.onSkipToNext()
            assertEquals(listOf(WheelKey(KeyEvent.KEYCODE_MEDIA_NEXT, 12)), captures)
            assertTrue(sends.isEmpty())
            assertTrue(SteeringWheelMappings.save())
            assertEquals("87:12", context.getSharedPreferences("carplay_wheel_keys", 0).getString("NEXT", null))
        } finally {
            SteeringWheelMappings.cancel()
            SteeringWheelMappings.clear(context, WheelAction.NEXT)
        }
    }
}
