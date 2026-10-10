package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class MicrophonePacketizerTest {
    @Test
    fun packetUsesRtpHeaderAndInputKeyAad() {
        val key = ByteArray(32) { index -> (index + 1).toByte() }
        val counters = MicrophoneCounters()
        val body = byteArrayOf(0x28, 0x01, 0x02, 0x03)

        val packet = MicrophonePacketizer.sealPacket(key, 100, counters, body, 480)

        assertEquals(0x80, packet[0].toInt() and 0xff)
        assertEquals(100, packet[1].toInt() and 0xff)
        assertEquals(0, ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff))
        assertEquals(
            0,
            ((packet[4].toInt() and 0xff) shl 24) or
                ((packet[5].toInt() and 0xff) shl 16) or
                ((packet[6].toInt() and 0xff) shl 8) or
                (packet[7].toInt() and 0xff),
        )

        val sealedEnd = packet.size - MicrophonePacketizer.NONCE_LEN
        val nonce = ByteArray(12)
        packet.copyInto(nonce, 4, sealedEnd, packet.size)
        val opened = AirPlayCrypto.chachaOpen(
            key,
            nonce,
            packet.copyOfRange(MicrophonePacketizer.RTP_HEADER_LEN, sealedEnd),
            packet.copyOfRange(4, MicrophonePacketizer.RTP_HEADER_LEN),
        )

        assertArrayEquals(body, opened)
        assertEquals(1, counters.sequence)
        assertEquals(480, counters.timestamp)
        assertEquals(1L, counters.nonce)
    }

    @Test
    fun pcmIsConvertedToBigEndian() {
        assertArrayEquals(
            byteArrayOf(0x12, 0x34, 0x56, 0x78),
            MicrophonePacketizer.toWirePcm(
                byteArrayOf(0x34, 0x12, 0x78, 0x56),
            ),
        )
    }

    @Test
    fun observedSiriOpusUses24KhzRtpClockWithoutChanging48KhzCapture() {
        assertEquals(24_000, MicrophoneConfig.opusClockRate(0x20000000L))
        assertEquals(48_000, MicrophoneConfig.opusClockRate(0x40000000L))
        assertEquals(48_000, MicrophoneConfig.opusClockRate(0x10000000L))
        assertEquals(48_000, MicrophoneConfig.opusClockRate(0L))

        val siri = config(opusClockRate = MicrophoneConfig.opusClockRate(0x20000000L))
        assertEquals(960, siri.samplesPerPacket)
        assertEquals(1920, siri.frameBytes)
        assertEquals(480, siri.rtpSamplesPerPacket)
        assertEquals(960, config().rtpSamplesPerPacket)

        val counters = MicrophoneCounters()
        repeat(3) { index ->
            val packet = MicrophonePacketizer.sealPacket(
                ByteArray(32) { (it + 1).toByte() }, 100, counters,
                byteArrayOf(0xf8.toByte(), index.toByte()), siri.rtpSamplesPerPacket,
            )
            val timestamp = java.nio.ByteBuffer.wrap(packet, 4, 4)
                .order(java.nio.ByteOrder.BIG_ENDIAN).int
            assertEquals(index * 480, timestamp)
        }
        assertEquals(1440, counters.timestamp)
    }

    private fun config(opusClockRate: Int = 48_000) = MicrophoneConfig(
        audioType = "speechrecognition",
        sampleRate = 48_000,
        channels = 1,
        payloadType = 100,
        frameMillis = 20,
        host = InetAddress.getLoopbackAddress(),
        port = 1,
        key = ByteArray(32),
        codec = AudioCodecKind.OPUS,
        opusClockRate = opusClockRate,
    )
}
