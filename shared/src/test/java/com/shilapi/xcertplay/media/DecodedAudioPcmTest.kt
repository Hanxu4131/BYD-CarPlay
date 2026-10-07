package com.shilapi.xcertplay.media

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class DecodedAudioPcmTest {
    @Test fun copiesExactRangeAndReleasesBeforeAnyBlockingWrite() {
        val wire = byteArrayOf(9, 8, 1, 2, 3, 4, 7)
        val output = ByteBuffer.wrap(wire).apply { position(6) }
        val scratch = ByteArray(8) { 6 }
        val events = mutableListOf<String>()
        val owned = DecodedAudioPcm.copyAndRelease(
            source = { events.add("copy"); output }, offset = 2, length = 4, scratch = scratch,
            release = { events.add("release"); wire.fill(0) },
        )!!
        events.add("write")
        assertEquals(listOf("copy", "release", "write"), events)
        assertSame(scratch, owned)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 6, 6, 6, 6), owned)
    }

    @Test fun growsScratchWithoutRetainingCodecStorage() {
        val wire = byteArrayOf(9, 1, 2, 3, 8)
        val scratch = ByteArray(2)
        var releases = 0
        val owned = DecodedAudioPcm.copyAndRelease(
            source = { ByteBuffer.wrap(wire) }, offset = 1, length = 3, scratch = scratch,
            release = { releases++; wire.fill(0) },
        )!!
        assertNotSame(scratch, owned)
        assertArrayEquals(byteArrayOf(1, 2, 3), owned)
        assertEquals(1, releases)
    }

    @Test fun sourceAndCopyFailuresStillReleaseExactlyOnce() {
        for (sourceFails in listOf(false, true)) {
            var releases = 0
            try {
                DecodedAudioPcm.copyAndRelease(
                    source = {
                        if (sourceFails) throw IllegalStateException("output unavailable")
                        ByteBuffer.allocate(2)
                    },
                    offset = 1, length = 3, scratch = ByteArray(8), release = { releases++ },
                )
                fail("Copy must fail")
            } catch (_: RuntimeException) {
                assertEquals(1, releases)
            }
        }
    }

    @Test fun emptyOrUnavailableOutputReleasesWithoutProducingPcm() {
        for (length in listOf(0, 4)) {
            var releases = 0
            assertNull(DecodedAudioPcm.copyAndRelease(
                source = { if (length == 0) fail("Empty output does not need a buffer"); null },
                offset = 0, length = length, scratch = ByteArray(8), release = { releases++ },
            ))
            assertEquals(1, releases)
        }
    }

    @Test fun partialWritesUseOwnedPcmInOrderAfterRelease() {
        val wire = byteArrayOf(8, 10, 11, 12, 13, 14, 15, 9)
        var released = false
        val owned = DecodedAudioPcm.copyAndRelease(
            source = { ByteBuffer.wrap(wire) }, offset = 1, length = 6, scratch = ByteArray(16),
            release = { released = true; wire.fill(0) },
        )!!
        val written = mutableListOf<Byte>()
        var offset = 0
        for (count in listOf(2, 1, 3)) {
            assertTrue(released)
            for (index in offset until offset + count) written.add(owned[index])
            offset += count
        }
        assertEquals(6, offset)
        assertArrayEquals(byteArrayOf(10, 11, 12, 13, 14, 15), written.toByteArray())
    }
}
