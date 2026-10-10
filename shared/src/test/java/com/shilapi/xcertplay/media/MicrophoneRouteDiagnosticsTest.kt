package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneRouteDiagnosticsTest {
    @Test fun mappingContainsOnlyReportedDeviceIdAndChannelsWithoutSeatInference() {
        assertEquals("id=21 group=0 index=1 channels=[0:1,1:0]",
            microphoneMappingLabel(21, 0, 1, listOf(0 to 1, 1 to 0)))
        assertEquals("id=-1 group=-1 index=-1 channels=[]",
            microphoneMappingLabel(-1, -1, -1, emptyList()))
    }
}
