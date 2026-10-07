package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophonePcmFramesTest {
    @Test fun lowSampleRatesReadOneTwentyMillisecondPacket() {
        for (rate in listOf(8_000, 16_000, 24_000, 48_000)) {
            val frames = MicrophonePcmFrames(rate * 20 / 1000 * 2)
            assertEquals(rate * 20 / 1000 * 2, frames.readSize)
        }
    }

    @Test fun shortReadsPreserveBytesAndNeverEmitIncompleteFrame() {
        val frames = MicrophonePcmFrames(8)
        val output = mutableListOf<ByteArray>()
        val source = ByteArray(24) { it.toByte() }
        var offset = 0
        for (size in listOf(3, 0, 6, 2, 13)) {
            frames.append(source.copyOfRange(offset, offset + size), size) { output.add(it.copyOf()) }
            offset += size
            assertEquals(offset / 8, output.size)
        }
        assertArrayEquals(source, output.fold(byteArrayOf()) { result, frame -> result + frame })
    }

    @Test fun bothEffectsDefaultOffAndCanBeSelectedIndependently() {
        assertEquals(MicrophoneProcessing(false, false), MicrophoneProcessing())
        assertEquals(false, MicrophoneProcessing(noiseSuppression = true).echoCancellation)
        assertEquals(false, MicrophoneProcessing(echoCancellation = true).noiseSuppression)
    }
}
