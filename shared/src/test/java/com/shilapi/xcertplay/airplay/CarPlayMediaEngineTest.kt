package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.Socket
import java.math.BigInteger
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayMediaEngineTest {
    @Test
    fun streamConnectionIdUsesUnsignedDecimalForHkdfSalt() {
        assertEquals("18446744073709551615", unsignedPlistDecimal(-1L))
        assertEquals(
            BigInteger("18446744073709551615"),
            unsignedPlistInteger(-1L),
        )
    }

    @Test
    fun screenStreamTeardownReportsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()

        try {
            val engine = CarPlayMediaEngine(sink)
            engine.onTeardown(session, 110)
            engine.onTeardown(session, 100)
        } finally {
            session.close()
        }

        assertEquals(listOf(110 to false), events)
    }

    @Test
    fun sessionCloseReportsAllScreenStreamsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink)
        val streamsField = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val streams = streamsField.get(engine) as
            MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        streams[CarPlayMediaEngine.StreamKey(session, 110)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 111)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 100)] = Closeable {}

        engine.onSessionClosed(session)
        session.close()

        assertEquals(setOf(110 to false, 111 to false), events.toSet())
        assertTrue(streams.isEmpty())
    }

    @Test
    fun videoRemoteControlSessionsAreAcceptedOnlyWithVideoInCar() {
        val stream = mapOf("type" to 130L, "clientTypeUUID" to "A6B27562-B43A-4F2D-B75F-82391E250194", "controlType" to 1L)
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val plain = testSession()
        val video = testSession(videoInCar = true)
        try {
            assertNull(engine.onDataStream(plain, stream))
            val first = engine.onDataStream(video, stream)
            assertEquals(130, first?.get("type"))
            assertEquals(3L, first?.get("streamID"))
            assertEquals(4L, engine.onDataStream(video, stream)?.get("streamID"))
        } finally {
            plain.close()
            video.close()
        }
    }

    @Test fun alternateScreenEofKeepsMainAndAudioConnected() {
        val inactive = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Boolean>>())
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
                if (type == 111 && !active) inactive.countDown()
            }
        })
        val session = testSession()
        try {
            val mainPort = engine.onScreen(session, 110, screenSetup())!!
            val altPort = engine.onScreen(session, 111, screenSetup())!!
            val audio = Closeable {}
            streams(engine)[CarPlayMediaEngine.StreamKey(session, 100, "media")] = audio
            Socket("127.0.0.1", altPort).use { it.shutdownOutput() }
            assertTrue("Alternate EOF must be handled", inactive.await(3, TimeUnit.SECONDS))
            assertFalse(session.isClosed)
            assertNotNull(streams(engine)[CarPlayMediaEngine.StreamKey(session, 110)])
            assertSame(audio, streams(engine)[CarPlayMediaEngine.StreamKey(session, 100, "media")])
            assertNull(streams(engine)[CarPlayMediaEngine.StreamKey(session, 111)])
            assertFalse(events.contains(110 to false))
            assertTrue(mainPort > 0)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun replacedAlternateCallbacksCannotTouchItsReplacement() {
        val events = mutableListOf<String>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) { events += "active=$active" }
            override fun onVideoCodec(type: Int, codec: VideoCodec) { events += "codec" }
            override fun onVideoConfig(type: Int, codecData: ByteArray) { events += "config" }
            override fun onVideoGeometry(type: Int, codec: VideoCodec, geometry: MainAreaViewport?) { events += "geometry" }
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) { events += "frame" }
        })
        val session = testSession()
        val key = CarPlayMediaEngine.StreamKey(session, 111)
        try {
            engine.onScreen(session, 111, screenSetup())
            val old = streams(engine)[key] as ScreenStream
            val listener = ScreenStream::class.java.getDeclaredField("listener").apply { isAccessible = true }
                .get(old) as ScreenStream.Listener
            engine.onScreen(session, 111, screenSetup())
            val replacement = streams(engine)[key]
            events.clear()
            listener.onCodec(VideoCodec.H264)
            listener.onConfig(byteArrayOf(1))
            listener.onGeometry(VideoCodec.H264, null)
            listener.onFrame(byteArrayOf(2))
            listener.onClosed(null)
            assertTrue(events.isEmpty())
            assertSame(replacement, streams(engine)[key])
            assertFalse(session.isClosed)
            engine.onTeardown(session, 111)
            assertEquals(listOf("active=false"), events)
            assertNull(streams(engine)[key])
            assertTrue(streamClosed(replacement as ScreenStream))
            listener.onClosed(null)
            assertEquals(listOf("active=false"), events)
            assertFalse(session.isClosed)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun mainScreenEofStillEndsTheSession() {
        val session = testSession()
        val engine = CarPlayMediaEngine(object : MediaSink {})
        try {
            engine.onScreen(session, 110, screenSetup())
            val screen = streams(engine)[CarPlayMediaEngine.StreamKey(session, 110)] as ScreenStream
            engine.screenClosed(session, 110, screen, false, null)
            assertTrue(session.isClosed)
            assertTrue(streamClosed(screen))
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun closingOldSessionDoesNotDeactivateReplacementAlternateRenderer() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) { events += type to active }
        })
        val oldSession = testSession()
        val currentSession = testSession()
        try {
            engine.onScreen(oldSession, 111, screenSetup())
            val oldScreen = streams(engine)[CarPlayMediaEngine.StreamKey(oldSession, 111)] as ScreenStream
            engine.onScreen(currentSession, 111, screenSetup())
            val currentScreen = streams(engine)[CarPlayMediaEngine.StreamKey(currentSession, 111)] as ScreenStream
            events.clear()
            engine.onSessionClosed(oldSession)
            assertTrue(events.isEmpty())
            assertTrue(streamClosed(oldScreen))
            assertFalse(streamClosed(currentScreen))
            engine.onSessionClosed(currentSession)
            assertEquals(listOf(111 to false), events)
            assertTrue(streamClosed(currentScreen))
            assertTrue(streams(engine).isEmpty())
        } finally {
            engine.onSessionClosed(oldSession)
            engine.onSessionClosed(currentSession)
            oldSession.close()
            currentSession.close()
        }
    }

    @Test fun alternateFailureIsIsolatedButMainFailureStillClosesSession() {
        val session = testSession()
        val engine = CarPlayMediaEngine(object : MediaSink {})
        try {
            engine.onScreen(session, 110, screenSetup())
            engine.onScreen(session, 111, screenSetup())
            val alt = streams(engine)[CarPlayMediaEngine.StreamKey(session, 111)] as ScreenStream
            engine.screenClosed(session, 111, alt, false, java.io.IOException("invalid video body size"))
            assertFalse(session.isClosed)
            assertTrue(streamClosed(alt))
            val main = streams(engine)[CarPlayMediaEngine.StreamKey(session, 110)] as ScreenStream
            engine.screenClosed(session, 110, main, false, java.io.EOFException("truncated video header"))
            assertTrue(session.isClosed)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun firstFrameNotificationRunsOutsideScreenOwnershipLockAndIgnoresRetiredStreams() {
        val diagnostics = mutableListOf<(String) -> Unit>()
        lateinit var engine: CarPlayMediaEngine
        var rendered = 0
        val session = testSession(listener = object : AirPlaySessionListener {
            override fun onVideoFrameRendered(session: AirPlaySession) {
                val lock = CarPlayMediaEngine::class.java.getDeclaredField("screenOwnershipLock")
                    .apply { isAccessible = true }.get(engine)
                assertFalse("External first-frame callbacks must not hold video ownership", Thread.holdsLock(lock))
                rendered++
            }
        })
        engine = CarPlayMediaEngine(object : MediaSink {
            override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) { diagnostics += handler }
        })
        try {
            engine.onScreen(session, 111, screenSetup())
            diagnostics.last()("first frame rendered")
            assertEquals(1, rendered)
            engine.onScreen(session, 111, screenSetup())
            diagnostics.first()("first frame rendered")
            assertEquals(1, rendered)
            diagnostics.last()("first frame rendered")
            assertEquals(2, rendered)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    private fun screenSetup(): Map<String, Any?> = mapOf("streamConnectionID" to 42L)

    private fun streamClosed(stream: ScreenStream): Boolean =
        (ScreenStream::class.java.getDeclaredField("closed").apply { isAccessible = true }
            .get(stream) as AtomicBoolean).get()

    @Suppress("UNCHECKED_CAST")
    private fun streams(engine: CarPlayMediaEngine): MutableMap<CarPlayMediaEngine.StreamKey, Closeable> =
        CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
            .get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, Closeable>

    private fun testSession(videoInCar: Boolean = false, listener: AirPlaySessionListener = object : AirPlaySessionListener {}): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            videoInCar = videoInCar,
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = listener,
        media = object : AirPlayMediaHandler {},
    ).also { session ->
        session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, ByteArray(32))
    }
}
