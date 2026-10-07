package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.Socket
import java.security.SecureRandom
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayAudioStreamIsolationTest {
    @Test fun malformedTeardownReturns400AndPreservesActiveStreams() {
        val session = session()
        session.activeStreams.addAll(listOf(110, 111, 102))
        val handler = AirPlaySession::class.java.getDeclaredMethod("handleTeardown", RtspMessage.Request::class.java)
            .apply { isAccessible = true }
        try {
            listOf(
                byteArrayOf(1, 2, 3),
                BplistCodec.encode(mapOf("streams" to listOf(mapOf("streamID" to 7L)))),
            ).forEach { body ->
                val response = handler.invoke(session, RtspMessage.Request("TEARDOWN", "/", "RTSP/1.0", emptyMap(), body))
                    as RtspMessage.Response
                assertEquals(400, response.status)
                assertEquals(setOf(110, 111, 102), session.activeStreams)
            }
        } finally {
            session.close()
        }
    }

    @Test fun replacedScreenEofCannotCloseTheCurrentSession() {
        val session = session()
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val old = ScreenStream(ByteArray(32))
        val current = ScreenStream(ByteArray(32))
        val key = CarPlayMediaEngine.StreamKey(session, 110)
        streams(engine)[key] = current
        try {
            engine.screenClosed(session, 110, old, false, null)
            assertSame(current, streams(engine)[key])
            val closed = AirPlaySession::class.java.getDeclaredField("closed").apply { isAccessible = true }
                .get(session) as java.util.concurrent.atomic.AtomicBoolean
            assertFalse(closed.get())
            engine.screenClosed(session, 110, current, false, null)
            assertTrue(closed.get())
        } finally {
            old.close()
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun sessionCloseStopsItsAudioAndPreservesOtherSessionFeedback() {
        val stopped = mutableListOf<AudioStreamId>()
        val microphonesStopped = mutableListOf<AudioStreamId>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { stopped += id }
            override fun onMicrophoneStopped(id: AudioStreamId) { microphonesStopped += id }
        })
        val first = session()
        val second = session()
        try {
            assertNotNull(engine.onAudio(first, 100, setup("telephony")))
            assertNotNull(engine.onAudio(second, 102, setup("media")))
            @Suppress("UNCHECKED_CAST")
            val microphones = CarPlayMediaEngine::class.java.getDeclaredField("pendingMicrophone").apply { isAccessible = true }
                .get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, MicrophoneConfig>
            microphones[CarPlayMediaEngine.StreamKey(first, 100, "telephony")] = MicrophoneConfig(
                "telephony", 48000, 1, 100, 20, java.net.InetAddress.getLoopbackAddress(), 1, ByteArray(32),
            )
            val firstFeedback = engine.onFeedback(first)?.get("streams") as List<*>
            assertEquals(1, firstFeedback.size)
            stopped.clear()
            engine.onSessionClosed(first)
            assertEquals(listOf(AudioStreamId(100, "telephony")), stopped)
            assertEquals(listOf(AudioStreamId(100, "telephony")), microphonesStopped)
            assertEquals(setOf(CarPlayMediaEngine.StreamKey(second, 102, "media")), streams(engine).keys)
            val feedback = engine.onFeedback(second)?.get("streams") as List<*>
            assertEquals(1, feedback.size)
        } finally {
            engine.onSessionClosed(first)
            engine.onSessionClosed(second)
            first.close()
            second.close()
        }
    }

    @Test fun lateSessionCloseDoesNotStopReplacementWithTheSameAudioIdentity() {
        val stopped = mutableListOf<AudioStreamId>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { stopped += id }
        })
        val old = session(); val replacement = session()
        try {
            assertNotNull(engine.onAudio(old, 100, setup("telephony")))
            assertNotNull(engine.onAudio(replacement, 100, setup("telephony")))
            stopped.clear()
            engine.onSessionClosed(old)
            assertTrue(stopped.isEmpty())
            assertEquals(setOf(CarPlayMediaEngine.StreamKey(replacement, 100, "telephony")), streams(engine).keys)
            engine.onSessionClosed(replacement)
            assertEquals(listOf(AudioStreamId(100, "telephony")), stopped)
        } finally {
            engine.onSessionClosed(old); engine.onSessionClosed(replacement)
            old.close(); replacement.close()
        }
    }

    @Test fun guidanceSetupAndMediaReplacementKeepTheOtherAudioStreamAlive() {
        val session = session()
        val engine = CarPlayMediaEngine(object : MediaSink {})
        try {
            assertNotNull(engine.onAudio(session, 100, setup("media")))
            val streams = streams(engine)
            val mediaKey = CarPlayMediaEngine.StreamKey(session, 100, "media")
            val firstMedia = streams[mediaKey]
            assertNotNull(engine.onAudio(session, 100, setup("default")))
            val guidanceKey = CarPlayMediaEngine.StreamKey(session, 100, "default")
            val guidance = streams[guidanceKey]
            assertSame(firstMedia, streams[mediaKey])
            assertEquals(2, streams.size)
            assertNotNull(engine.onAudio(session, 100, setup("MEDIA")))
            assertNotSame(firstMedia, streams[mediaKey])
            assertSame(guidance, streams[guidanceKey])
            assertEquals(2, streams.size)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun typeTeardownClosesEveryVariantButKeepsOtherTypes() {
        val stopped = mutableListOf<AudioStreamId>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { stopped.add(id) }
        })
        val session = session()
        try {
            assertNotNull(engine.onAudio(session, 100, setup("media")))
            assertNotNull(engine.onAudio(session, 100, setup("default")))
            assertNotNull(engine.onAudio(session, 102, setup("media")))
            stopped.clear()
            engine.onTeardown(session, 100)
            assertEquals(setOf(AudioStreamId(100, "media"), AudioStreamId(100, "default")), stopped.toSet())
            assertEquals(setOf(CarPlayMediaEngine.StreamKey(session, 102, "media")), streams(engine).keys)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    private fun setup(audioType: String): Map<String, Any?> = mapOf(
        "audioType" to audioType,
        "audioFormat" to 0x8000L,
        "streamConnectionID" to 42L,
    )

    @Suppress("UNCHECKED_CAST")
    private fun streams(engine: CarPlayMediaEngine): MutableMap<CarPlayMediaEngine.StreamKey, Closeable> =
        CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
            .get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, Closeable>

    private fun session(): AirPlaySession {
        val session = AirPlaySession(
            socket = Socket(),
            config = AirPlayConfig(
                deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {}, media = object : AirPlayMediaHandler {},
        )
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, secret)
        return session
    }
}
