package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test

class AdaptiveVideoConfigTest {
    @Test fun viewportOnlyChangeKeepsDecoderWhileCodedResizeReconfigures() {
        val original = VideoJob.Config(VideoCodec.H264, byteArrayOf(1,2),1920,1080)
        assertTrue(original.sameDecoderConfig(original.copy(codecData=byteArrayOf(1,2)),1920,1080))
        assertTrue(original.sameDecoderConfig(original.copy(codedWidth=null,codedHeight=null),1920,1080))
        assertFalse(original.sameDecoderConfig(original.copy(codedWidth=960),1920,1080))
        assertFalse(original.sameDecoderConfig(original.copy(codecData=byteArrayOf(1,3)),1920,1080))
        assertFalse(original.sameDecoderConfig(original.copy(codec=VideoCodec.H265),1920,1080))
    }
}
