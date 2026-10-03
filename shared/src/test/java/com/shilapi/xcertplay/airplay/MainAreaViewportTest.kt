package com.shilapi.xcertplay.airplay

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class MainAreaViewportTest {
    private fun header(cw: Float = 1920f, ch: Float = 1080f, x: Float = 0f, y: Float = 0f,
        w: Float = 960f, h: Float = 1080f): ByteArray = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN).apply {
        putFloat(16, cw); putFloat(20, ch); putFloat(32, x); putFloat(36, y); putFloat(40, w); putFloat(44, h)
    }.array()

    @Test fun parsesFixedCanvasViewportAndRejectsMalformedGeometry() {
        assertEquals(MainAreaViewport(1920, 1080, 0, 0, 960, 1080), MainAreaViewport.parse(header()))
        assertNull(MainAreaViewport.parse(header(w = Float.NaN)))
        assertNull(MainAreaViewport.parse(header(x = -1f)))
        assertNull(MainAreaViewport.parse(header(x = 1500f)))
        assertNull(MainAreaViewport.parse(header(w = 0f)))
        assertNull(MainAreaViewport.parse(header(cw = 100000f)))
        assertNull(MainAreaViewport.parse(ByteArray(16)))
    }

    @Test fun commandCannotConfirmAndOnlyLatestDeclaredAreaCanMatch() {
        val state = MainAreaSelection(1920, 1080, listOf(MainViewArea(1920,1080), MainViewArea(960,1080)), 0)
        val split = state.select(960,1080)!!
        assertNull(state.confirmed)
        assertFalse(state.current(split.first, split.second)) // no received H264
        assertFalse(state.receive(VideoCodec.H264, MainAreaViewport(1920,1080,0,0,1920,1080)))
        assertTrue(state.markRequested(split.first, split.second))
        assertFalse(state.markRequested(split.first, split.second))
        val full = state.select(1920,1080)!!
        assertFalse(state.current(split.first, split.second))
        assertFalse(state.receive(VideoCodec.H264, MainAreaViewport.parse(header())))
        assertTrue(state.current(full.first, full.second))
        assertTrue(state.receive(VideoCodec.H264, MainAreaViewport(1920,1080,0,0,1920,1080)))
        state.reset()
        assertNull(state.confirmed)
        assertFalse(state.current(full.first, full.second))
    }

    @Test fun hevcAndUnknownAreasKeepFit() {
        val state = MainAreaSelection(1920,1080,listOf(MainViewArea(960,1080)),0)
        assertFalse(state.receive(VideoCodec.H265, MainAreaViewport.parse(header())))
        assertNull(state.confirmed)
        assertFalse(state.receive(VideoCodec.H264, MainAreaViewport(1920,1080,0,0,0,1080)))
        assertNull(state.select(1234,900))
        assertTrue(state.receive(VideoCodec.H264, MainAreaViewport.parse(header())))
        assertNotNull(state.confirmed)
    }

    @Test fun viewportTouchUsesOriginalHidCanvas() {
        val view = MainAreaViewport(1920,1080,100,40,960,900)
        val contact = view.map(AirPlayContact(1,1.0,0.5,true),1920,1080)
        assertEquals(1060.0 / 1920, contact.x, 0.000001)
        assertEquals(490.0 / 1080, contact.y, 0.000001)
        assertTrue(contact.down)
    }

    @Test fun plistKeepsMaximumCanvasAndDeclaresFiniteAreas() {
        val config = AirPlayConfig("test","02:00:00:00:00:02","02:00:00:00:00:02","366.0",
            AirPlayDisplayConfig(1920,1080,adaptiveViewAreas=listOf(MainViewArea(1920,1080),MainViewArea(960,1080)),initialViewArea=1))
        val display = (AirPlayInfoPlist.build(config)["displays"] as List<*>).single() as Map<*,*>
        assertEquals(1920,display["widthPixels"])
        assertEquals(1,display["initialViewArea"])
        assertEquals(2,(display["viewAreas"] as List<*>).size)
        val split = (display["viewAreas"] as List<*>)[1] as Map<*,*>
        assertEquals(960,split["widthPixels"])
        assertEquals(emptyList<Int>(),split["adjacentViewAreas"])
        assertEquals(AirPlayInfoPlist.MAIN_UUID,display["uuid"])
    }
    @Test fun cornerMaskCapabilityIsMainOnlyAndCanBeDisabled() {
        fun displays(enabled: Boolean) = AirPlayInfoPlist.build(AirPlayConfig("test","02:00:00:00:00:02","02:00:00:00:00:02","366.0",
            AirPlayDisplayConfig(1920,1080,separateCornerMasks=enabled), cluster=AirPlayDisplayConfig(1920,720,separateCornerMasks=true)))["displays"] as List<*>
        val enabled = displays(true).map { it as Map<*,*> }
        assertEquals(true, enabled.first()["cornerMasks"])
        assertFalse(enabled.last().containsKey("cornerMasks"))
        assertFalse((displays(false).first() as Map<*,*>).containsKey("cornerMasks"))
    }

    @Test fun transientUnknownSizeAndLateOldGeometryPreserveConfirmedViewport() {
        val state = MainAreaSelection(1920,1080,listOf(MainViewArea(1920,1080),MainViewArea(1284,990)),1)
        val split = MainAreaViewport(1920,1080,0,0,1284,990)
        assertTrue(state.receive(VideoCodec.H264,split))
        val current = state.select(1284,990)!!
        assertNull(state.select(1284,1078))
        assertEquals(split,state.confirmed)
        assertTrue(state.current(current.first,current.second))
        assertFalse(state.receive(VideoCodec.H264,MainAreaViewport(1920,1080,0,0,1920,1080)))
        assertEquals(split,state.confirmed)
    }

    @Test fun retriesHaveThreeAttemptLimitAndOldEpochIsCancelled() {
        val state = MainAreaSelection(1920,1080,listOf(MainViewArea(1920,1080),MainViewArea(1284,990)),0)
        state.receive(VideoCodec.H264,null)
        val split = state.select(1284,990)!!
        val owner = Any()
        assertTrue(state.beginRequestSeries(split.first,split.second,owner))
        assertFalse(state.beginRequestSeries(split.first,split.second,owner))
        repeat(3) { assertTrue(state.markRequested(split.first,split.second,3)) }
        assertFalse(state.markRequested(split.first,split.second,3))
        state.select(1920,1080)
        assertFalse(state.ownsRequestSeries(split.first,split.second,owner))
        assertFalse(state.markRequested(split.first,split.second,3))
    }

    @Test fun newHostTakesOverRemainingRetriesAndMatchingResponseStopsNewSeries() {
        val state = MainAreaSelection(1920,1080,listOf(MainViewArea(1920,1080),MainViewArea(1284,990)),1)
        state.receive(VideoCodec.H264,null)
        val request = state.select(1284,990)!!
        val oldHost = Any(); val newHost = Any()
        assertTrue(state.beginRequestSeries(request.first,request.second,oldHost))
        assertTrue(state.markRequested(request.first,request.second,3))
        assertTrue(state.beginRequestSeries(request.first,request.second,newHost))
        assertFalse(state.ownsRequestSeries(request.first,request.second,oldHost))
        assertTrue(state.ownsRequestSeries(request.first,request.second,newHost))
        assertTrue(state.markRequested(request.first,request.second,3))
        assertTrue(state.receive(VideoCodec.H264,MainAreaViewport(1920,1080,0,0,1284,990)))
        assertFalse(state.beginRequestSeries(request.first,request.second,Any()))
    }

}
