package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AndroidMediaSinkStateTest {
    @Test fun onlyTheMainScreenInheritsTheConstructorSurface() {
        val surface = Any()
        assertSame(surface, VideoSurfaceRouting.defaultFor(110, surface))
        assertNull(VideoSurfaceRouting.defaultFor(111, surface))
    }

    @Test fun duplicateExplicitScreenSurfaceIsDetectedForRejection() {
        val surface = Any()
        assertEquals(listOf(110), VideoSurfaceRouting.otherTypesUsing(111, surface, mapOf(110 to surface)))
        assertTrue(VideoSurfaceRouting.otherTypesUsing(111, surface, mapOf(111 to surface)).isEmpty())
        assertTrue(VideoSurfaceRouting.otherTypesUsing(111, Any(), mapOf(110 to surface)).isEmpty())
    }

    @Test fun recreatingTheScreenRestoresItsActiveVideoState() {
        val sink = AndroidMediaSink()
        sink.onScreenStreamActive(110, true)
        sink.onScreenStreamActive(111, true)
        sink.onScreenStreamActive(111, false)
        val events = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> events.add(type to active) }
        assertEquals(listOf(110 to true), events)
        sink.close()
        assertEquals(listOf(110 to true, 110 to false), events)
        val afterClose = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> afterClose.add(type to active) }
        assertTrue(afterClose.isEmpty())
    }

    @Test fun playbackParameterSnapshotKeepsValuesAndContainsPlatformFailures() {
        assertEquals(
            PlaybackParamsSnapshot(1.25f, 0.9f),
            PlaybackParamsSnapshot.capture { 1.25f to 0.9f },
        )
        assertEquals(
            "playbackSpeed=unavailable playbackPitch=unavailable",
            PlaybackParamsSnapshot.capture { error("PlaybackParams unavailable") }.logFields(),
        )
    }
}
