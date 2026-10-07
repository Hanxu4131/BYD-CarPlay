package com.shilapi.xcertplay.camera

import org.junit.Assert.*
import org.junit.Test

class CameraFrameMapperTest {
    @Test fun cropKeepsPaddedLumaAndInterleavedChromaOrder() {
        val source=CameraFrameLayout(8,4,10,10,CameraPixelFormat.NV21)
        val input=ByteArray(requireNotNull(source.payloadBytes())) { it.toByte() }
        val region=CameraFrameRegion(2,2,4,2)
        val output=ByteArray(requireNotNull(region.layout(source).payloadBytes()))
        CameraFrameMapper.crop(input,source,region,output)
        assertArrayEquals(byteArrayOf(22,23,24,25,32,33,34,35,52,53,54,55),output)
        assertEquals(CameraPixelFormat.NV21,region.layout(source).format)
    }
    @Test fun unsafeRegionAndUnknownSourceAreRejected() {
        val source=CameraFrameLayout(8,4,8,8,CameraPixelFormat.NV12)
        assertFalse(CameraFrameRegion(1,0,2,2).valid(source))
        assertFalse(CameraFrameRegion(6,2,4,2).valid(source))
        assertFalse(CameraFrameRegion(0,0,Int.MAX_VALUE-1,2).valid(source))
        val plan=CameraCaptureProfiles.confirmedBydAvm()
        try { plan.copy(source=plan.source.copy(userVerifiedSource=false)).validate();fail() } catch (_:IllegalArgumentException) {}
    }
    @Test fun sharedPhysicalRegionIsCopiedOnceForTwoViews() {
        val source=CameraFrameLayout(4,4,4,4,CameraPixelFormat.NV21)
        val region=CameraFrameRegion(0,0,2,2)
        val mapper=CameraFrameMapper(source,mapOf(CameraView.LEFT_FRONT to region,CameraView.LEFT_REAR to region))
        val seen=mutableListOf<ByteArray>()
        mapper.dispatch(ByteArray(24) { 7 }) { _,bytes,_ -> seen.add(bytes) }
        assertEquals(2,seen.size);assertSame(seen[0],seen[1])
        val held=seen[0]
        mapper.dispatch(ByteArray(24) { 8 }) { _,bytes,_ -> assertSame(held,bytes);assertEquals(8,bytes[0].toInt()) }
    }
    @Test fun ownerVerifiedProfileUsesColumnOrderedPhysicalQuadrants() {
        val plan=CameraCaptureProfiles.confirmedBydAvm();plan.validate()
        assertEquals(CameraFrameRegion(0,960,1280,960),plan.regions.getValue(CameraView.LEFT_FRONT))
        assertEquals(plan.regions[CameraView.LEFT_FRONT],plan.regions[CameraView.LEFT_REAR])
        assertEquals(CameraFrameRegion(1280,0,1280,960),plan.regions.getValue(CameraView.RIGHT_REAR))
        assertEquals(CameraFrameRegion(0,0,1280,960),plan.regions.getValue(CameraView.REAR))
    }
}
