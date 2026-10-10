package com.shilapi.xcertplay

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.time.Duration
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostDisplaySizeTest {
    private lateinit var activity: CarPlayHostActivity
    private val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        (getField("teardownExecutor") as ExecutorService).shutdownNow()
        setField("teardownExecutor", PausedExecutorService())
        CarPlayBackgroundSession::class.java.getDeclaredField("owner").apply { isAccessible = true }
            .set(CarPlayBackgroundSession, activity)
        setField("activeDisplaySize", size(1920, 990))
    }

    @After fun tearDown() {
        (getField("shuttingDown") as AtomicBoolean).set(true)
        (getField("teardownExecutor") as ExecutorService).shutdownNow()
        (getField("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    @Test fun surroundViewOpenAndCloseKeepsTheNegotiatedCanvas() {
        val display = startSession()
        applySize(1920, 942)
        assertEquals(size(1920, 942), getField("activeDisplaySize"))
        applySize(1920, 990)
        assertSame(display, getField("sessionDisplay"))
        assertEquals(0, getField("restartGeneration"))
        assertFalse(getField("handshakeResetInProgress") as Boolean)
        assertEquals(2, keepLogs())
    }

    @Test fun aNarrowWindowIsNotTreatedAsScreenRotation() {
        val display = startSession()
        applySize(700, 990)
        assertSame(display, getField("sessionDisplay"))
        assertEquals(0, getField("restartGeneration"))
        assertEquals(1, keepLogs())
    }

    @Test fun movingBetweenDisplaysKeepsTheSessionWhenTheWindowShrinks() {
        val display = startSession(displayId = 28)
        applySize(1284, 990)
        assertEquals(size(1284, 990), getField("activeDisplaySize"))
        assertSame(display, getField("sessionDisplay"))
        assertEquals(0, getField("restartGeneration"))
        assertFalse(getField("handshakeResetInProgress") as Boolean)
    }

    @Test fun returningFromALauncherDisplayKeepsTheSessionAtTheSameSize() {
        val display = startSession(windowWidth = 1284, displayId = 28)
        applySize(1284, 990)
        assertSame(display, getField("sessionDisplay"))
        assertEquals(0, getField("restartGeneration"))
    }

    @Test fun launcherToFullscreenAndBackKeepsItsOriginalCanvas() {
        val display = startSession(windowWidth = 1284, displayId = 28)
        applySize(1920, 990)
        assertEquals(size(1920, 990), getField("activeDisplaySize"))
        applySize(1284, 990)
        assertSame(display, getField("sessionDisplay"))
        assertEquals(1284, display.width)
        assertEquals(990, display.height)
        assertEquals(0, getField("restartGeneration"))
        assertFalse(getField("handshakeResetInProgress") as Boolean)
    }

    @Test fun connectingInANarrowWindowKeepsTheSessionWhenTheCameraCloses() {
        val display = startSession(windowWidth = 700)
        applySize(1920, 990)
        assertEquals(size(1920, 990), getField("activeDisplaySize"))
        assertSame(display, getField("sessionDisplay"))
        assertEquals(0, getField("restartGeneration"))
        assertFalse(getField("handshakeResetInProgress") as Boolean)
    }

    @Test fun connectingInAReducedHeightWindowKeepsTheSessionWhenTheCameraCloses() {
        val display = startSession(windowHeight = 942)
        applySize(1920, 990)
        assertSame(display, getField("sessionDisplay"))
        assertEquals(0, getField("restartGeneration"))
    }

    @Test fun aScaledDownCanvasKeepsTheSessionWhenTheOriginalWindowReturns() {
        val display = startSession(canvasWidth = 1536, canvasHeight = 792)
        applySize(700, 990)
        applySize(1920, 990)
        assertSame(display, getField("sessionDisplay"))
        assertEquals(0, getField("restartGeneration"))
    }

    @Test fun aScaledUpCanvasKeepsTheSessionWhenTheStartupWindowGrows() {
        val display = startSession(windowWidth = 700, canvasWidth = 1400, canvasHeight = 1980)
        applySize(1000, 990)
        assertSame(display, getField("sessionDisplay"))
        assertEquals(0, getField("restartGeneration"))
    }

    @Test fun actualScreenRotationStillRebuildsTheSession() {
        startSession(rotation = Surface.ROTATION_90)
        applySize(990, 1920)
        assertEquals(1, getField("restartGeneration"))
        assertTrue(getField("handshakeResetInProgress") as Boolean)
        assertNull(getField("sessionDisplay"))
    }

    @Test fun rotationIsHandledEvenIfTheViewSizeIsUnchanged() {
        startSession(rotation = Surface.ROTATION_180)
        scheduleSize(1920, 990)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals(1, getField("restartGeneration"))
    }

    @Test fun anExplicitBarLayoutChangeStillRebuildsTheSession() {
        startSession()
        setField("hideTopBar", false)
        applySize(1920, 942)
        assertEquals(1, getField("restartGeneration"))
    }

    @Test fun quickOpenAndCloseCancelsThePendingShrink() {
        startSession()
        scheduleSize(700, 990)
        scheduleSize(1920, 990)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals(size(1920, 990), getField("activeDisplaySize"))
        assertNull(getField("pendingDisplaySize"))
        assertEquals(0, getField("restartGeneration"))
    }

    @Test fun anOngoingHandshakeResetOnlyRecordsTheNewSize() {
        setField("handshakeResetInProgress", true)
        applySize(700, 990)
        assertEquals(size(700, 990), getField("activeDisplaySize"))
        assertEquals(0, getField("restartGeneration"))
    }

    @Test fun initialSizeDetectionKeepsTheNormalStartupPath() {
        setField("activeDisplaySize", null)
        applySize(1920, 990)
        assertEquals(size(1920, 990), getField("activeDisplaySize"))
        assertEquals(0, getField("restartGeneration"))
        assertEquals(0, keepLogs())
    }

    @Test fun resizeWithoutASessionKeepsTheExistingRestartPath() {
        applySize(700, 990)
        assertEquals(1, getField("restartGeneration"))
    }

    @Test fun textureTransformFitsTheNegotiatedCanvas() {
        startSession()
        val view = TextureView(activity).apply { layout(0, 0, 1920, 942) }
        setField("videoView", view)
        activity.javaClass.getDeclaredMethod("updateVideoLayout", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, 1920, 942)
        val points = floatArrayOf(0f, 0f, 1920f, 942f)
        view.getTransform(Matrix()).mapPoints(points)
        val content = CarPlayVideoLayout.fit(1920, 990, 1920, 942)
        assertArrayEquals(floatArrayOf(content.left, content.top, content.left + content.width,
            content.top + content.height), points, 0.001f)
    }

    @Test fun touchesStartingInABarStaySuppressedUntilRelease() {
        startSession()
        val view = View(activity).apply { layout(0, 0, 1920, 942) }
        touch(view, MotionEvent.ACTION_DOWN, 1f, 471f)
        assertEquals(true, getField("touchOutsideContent"))
        touch(view, MotionEvent.ACTION_MOVE, 960f, 471f)
        assertEquals(true, getField("touchOutsideContent"))
        touch(view, MotionEvent.ACTION_UP, 960f, 471f)
        assertEquals(false, getField("touchOutsideContent"))
        touch(view, MotionEvent.ACTION_DOWN, 960f, 471f)
        assertEquals(false, getField("touchOutsideContent"))
    }

    @Test fun launcherCanvasKeepsTheSameTouchCoordinatesAfterFullscreenGrowth() {
        val display = startSession(windowWidth = 1284, displayId = 28)
        fun mapped(width: Int, x: Float) = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, 742.5f, 0).let {
            try {
                val content = activity.javaClass.getDeclaredMethod("contentRect", Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, width, 990) as CarPlayVideoLayout
                CarPlayTouchMapper.contacts(it, content).single()
            } finally { it.recycle() }
        }
        // Quarter-width, three-quarter-height in the original 1284x990 phone picture.
        val narrowTouch = mapped(1284, 321f)
        val view = TextureView(activity).apply { layout(0, 0, 1920, 990) }
        setField("videoView", view)
        applySize(1920, 990)
        val fullTouch = mapped(1920, 639f) // 318px left bar + 321px in the same canvas.
        assertEquals(0.25, narrowTouch.x, 0.000001)
        assertEquals(0.75, narrowTouch.y, 0.000001)
        assertEquals(narrowTouch, fullTouch)
        assertSame(display, getField("sessionDisplay"))
        assertEquals(0, getField("restartGeneration"))

        val corners = floatArrayOf(0f, 0f, 1920f, 990f)
        view.getTransform(Matrix()).mapPoints(corners)
        assertArrayEquals(floatArrayOf(318f, 0f, 1602f, 990f), corners, 0.001f)

        val before = ShadowLog.getLogsForTag("xcertplay-usb").count { it.msg.startsWith("touch action=") }
        touch(view, MotionEvent.ACTION_DOWN, 32f, 742.5f)
        touch(view, MotionEvent.ACTION_MOVE, 639f, 742.5f)
        assertTrue(getField("touchOutsideContent") as Boolean)
        assertEquals(before, ShadowLog.getLogsForTag("xcertplay-usb").count { it.msg.startsWith("touch action=") })
        touch(view, MotionEvent.ACTION_UP, 639f, 742.5f)
        assertFalse(getField("touchOutsideContent") as Boolean)
    }

    @Test fun adoptingABackgroundSessionPreservesItsCanvasOnResize() {
        val display = CarPlaySessionDisplay(1536, 792, Surface.ROTATION_0, true, true, 1920, 990)
        val sink = AndroidMediaSink()
        val controller = CarPlayController(activity,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 1536, heightPixels = 792)),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {},
            object : AirPlayMediaHandler {}, {})
        try {
            CarPlayBackgroundSession.store(controller, sink, 1920, 990, Any(), display) {}
            setField("videoView", TextureView(activity).apply { layout(0,0,1284,990) })
            val adopted = activity.javaClass.getDeclaredMethod("adoptBackgroundSession")
                .apply { isAccessible = true }.invoke(activity)
            assertEquals(true, adopted)
            assertEquals(size(1284,990),getField("activeDisplaySize"))
            applySize(700, 990)
            assertSame(controller, getField("controller"))
            assertSame(sink, getField("sink"))
            assertEquals(display, getField("sessionDisplay"))
            assertEquals(display, CarPlayBackgroundSession.snapshot()?.display)
            assertFalse(controller.isClosed())
            assertEquals(0, getField("restartGeneration"))
            applySize(1920, 990)
            assertSame(display, getField("sessionDisplay"))
            assertEquals(0, getField("restartGeneration"))
            applySize(2000, 990)
            assertSame(controller, getField("controller"))
            assertSame(display, getField("sessionDisplay"))
            assertFalse(controller.isClosed())
            assertEquals(0, getField("restartGeneration"))
        } finally {
            controller.close()
            controller.awaitClosed(1000)
            sink.close()
        }
    }

    @Test fun adaptiveCropNeedsMatchingHeaderAndUnknownResizeKeepsFitWithoutRestart() {
        startSession(canvasWidth = 1920, canvasHeight = 990)
        val selection = MainAreaSelection(1920, 990, listOf(MainViewArea(1920, 990), MainViewArea(960, 990)), 1)
        val renderer = AndroidMediaSink(videoWidth = 1920, videoHeight = 990, adaptiveSelection = selection)
        try {
            setField("sink", renderer)
            val view = TextureView(activity).apply { layout(0, 0, 960, 990) }
            setField("videoView", view)
            val update = activity.javaClass.getDeclaredMethod("updateVideoLayout", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType).apply { isAccessible = true }
            fun points(): FloatArray = floatArrayOf(0f, 0f, 480f, 990f).also { view.getTransform(Matrix()).mapPoints(it) }
            update.invoke(activity, 960, 990)
            assertArrayEquals(floatArrayOf(0f, 247.5f, 480f, 742.5f), points(), 0.001f)
            assertTrue(selection.receive(VideoCodec.H264, MainAreaViewport(1920,990,0,0,960,990)))
            update.invoke(activity, 960, 990)
            assertArrayEquals(floatArrayOf(0f, 0f, 960f, 990f), points(), 0.001f)
            setField("hideTopBar", false)
            applySize(1234, 990)
            assertNotNull(selection.confirmed)
            assertEquals(0, getField("restartGeneration"))
            assertFalse(getField("handshakeResetInProgress") as Boolean)
        } finally { renderer.close() }
    }

    @Test fun twoPixelResizeJitterKeepsTheConfirmedAreaAndDoesNotStartCover() {
        startSession(canvasWidth = 1920, canvasHeight = 990)
        val selection = MainAreaSelection(1920,990,listOf(MainViewArea(1920,990),MainViewArea(960,990)),1)
        val geometry = MainAreaViewport(1920,990,0,0,960,990)
        assertTrue(selection.receive(VideoCodec.H264,geometry))
        val renderer = AndroidMediaSink(adaptiveSelection = selection)
        try {
            setField("sink",renderer)
            val request = activity.javaClass.getDeclaredMethod("requestAdaptiveArea",sizeClass).apply { isAccessible = true }
            request.invoke(activity,size(960,988))
            assertEquals(geometry,selection.confirmed)
            request.invoke(activity,size(960,990))
            assertEquals(geometry,selection.confirmed)
            assertFalse((getField("resizeTransition") as AdaptiveResizeTransition).active)
            request.invoke(activity,size(960,987))
            assertEquals(geometry,selection.confirmed)
            assertFalse((getField("resizeTransition") as AdaptiveResizeTransition).active)
        } finally { renderer.close() }
    }

    @Test fun handoffWithTransientHeightAlignsAnUnchangedSplitWindow() {
        withAdaptiveHandoff { selection, renderer, commands, texture, view ->
            val full = MainAreaViewport(1920,1080,0,0,1920,1080)
            assertEquals(size(1284,990),getField("activeDisplaySize"))
            view.layout(0,0,1284,1080)
            activity.javaClass.getDeclaredMethod("requestAdaptiveArea",sizeClass)
                .apply { isAccessible = true }.invoke(activity,size(1284,1080))
            scheduleSize(1284,1080)
            assertEquals(full,selection.confirmed)
            assertEquals(0,adaptiveAttempts(selection))

            view.layout(0,0,1284,990)
            val listener = getField("textureListener") as TextureView.SurfaceTextureListener
            listener.onSurfaceTextureSizeChanged(texture,1284,990)
            commands.runAll()
            val epoch = getField("adaptiveRequestEpoch") as Long
            assertTrue(selection.current(1,epoch))
            assertNull(selection.confirmed)
            assertEquals(1,adaptiveAttempts(selection))
            assertNull(getField("pendingDisplaySize"))
            assertTrue((getField("resizeTransition") as AdaptiveResizeTransition).active)
            assertEquals(0,getField("restartGeneration"))

            renderer.onVideoGeometry(110,VideoCodec.H264,MainAreaViewport(1920,1080,0,0,1284,990))
            shadowOf(Looper.getMainLooper()).idle()
            listener.onSurfaceTextureUpdated(texture)
            assertFalse((getField("resizeTransition") as AdaptiveResizeTransition).active)
            repeat(4) { scheduleSize(1284,990); applySize(1284,990) }
            commands.runAll()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
            commands.runAll()
            assertEquals(1,adaptiveAttempts(selection))
            assertFalse((getField("resizeTransition") as AdaptiveResizeTransition).active)
            assertEquals(epoch,getField("adaptiveRequestEpoch"))
        }
    }

    @Test fun unchangedSplitRetriesStayBoundedAfterHandoffTimeout() {
        withAdaptiveHandoff { selection, _, commands, _, view ->
            view.layout(0,0,1284,990)
            applySize(1284,990)
            commands.runAll()
            assertEquals(1,adaptiveAttempts(selection))
            val epoch = getField("adaptiveRequestEpoch") as Long
            repeat(2) {
                repeat(4) { scheduleSize(1284,988); scheduleSize(1284,990) }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                commands.runAll()
            }
            assertEquals(3,adaptiveAttempts(selection))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            commands.runAll()
            assertFalse((getField("resizeTransition") as AdaptiveResizeTransition).active)
            repeat(4) { scheduleSize(1284,990); applySize(1284,990) }
            commands.runAll()
            assertEquals(3,adaptiveAttempts(selection))
            assertTrue(selection.current(1,epoch))
            assertFalse((getField("resizeTransition") as AdaptiveResizeTransition).active)
            assertEquals(0,getField("restartGeneration"))
        }
    }

    private fun adaptiveAttempts(selection: MainAreaSelection): Int = selection.javaClass
        .getDeclaredField("requestAttempts").apply { isAccessible = true }.getInt(selection)

    private fun withAdaptiveHandoff(
        check: (MainAreaSelection, AndroidMediaSink, PausedExecutorService, SurfaceTexture, TextureView) -> Unit,
    ) {
        val areas = listOf(MainViewArea(1920,1080),MainViewArea(1284,990))
        val selection = MainAreaSelection(1920,1080,areas,0)
        assertTrue(selection.receive(VideoCodec.H264,MainAreaViewport(1920,1080,0,0,1920,1080)))
        val renderer = AndroidMediaSink(adaptiveSelection = selection)
        val config = AirPlayConfig(deviceName = "test",deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",sourceVersion = "1",
            main = AirPlayDisplayConfig(widthPixels = 1920,heightPixels = 1080,adaptiveViewAreas = areas))
        val identity = AirPlayIdentity.generate()
        val pairings = PairingStore()
        val media = object : AirPlayMediaHandler {}
        val session = AirPlaySession(Socket(),config,identity,pairings,null,object : AirPlaySessionListener {},media)
        val controller = CarPlayController(activity,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,identification = Iap2IdentificationConfig(
                name = "test",modelIdentifier = "test",manufacturer = "test",serialNumber = "test",
                firmwareVersion = "1",hardwareVersion = "1",carPlayUsbInterfaceNumber = 3)),
            config,identity,pairings,object : AirPlaySessionListener {},media,{})
        val commands = PausedExecutorService()
        (getField("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        setField("airPlayCommandExecutor",commands)
        val texture = SurfaceTexture(0)
        val view = TextureView(activity) // No layout yet: adoption must use the stored host size.
        try {
            setField("videoView",view)
            CarPlayBackgroundSession.store(controller,renderer,1284,990,Any(),
                CarPlaySessionDisplay(1920,1080,Surface.ROTATION_0,true,true,1920,1080)) {}
            assertEquals(true,activity.javaClass.getDeclaredMethod("adoptBackgroundSession")
                .apply { isAccessible = true }.invoke(activity))
            assertSame(controller,getField("controller"))
            assertSame(renderer,getField("sink"))
            setField("activeAirPlaySession",session)
            setField("currentSurfaceTexture",texture)
            check(selection,renderer,commands,texture,view)
        } finally {
            controller.close(); controller.awaitClosed(1000)
            session.close(); renderer.close(); texture.release()
        }
    }

    @Test fun firstUpgradedSplitHandshakeIsRememberedForFullscreenReconnect() {
        // This fixture deliberately skips onCreate and its transport setup.
        setField("airPlayIdentity", AirPlayIdentity.generate())
        AdaptiveDisplayPreferences.setEnabled(activity, true)
        setField("hevcEnabled", false)
        setField("maximumDetectedWidthPixels", 1920)
        setField("maximumDetectedHeightPixels", 1080)
        val create = activity.javaClass.getDeclaredMethod("createAirPlayConfig", sizeClass).apply { isAccessible = true }
        val splitConfig = (create.invoke(activity, size(1284, 990)) as AirPlayConfig).main
        assertEquals(1, splitConfig.initialViewArea)
        val fullConfig = (create.invoke(activity, size(1920, 1080)) as AirPlayConfig).main
        assertEquals(0, fullConfig.initialViewArea)
        assertEquals(splitConfig.adaptiveViewAreas, fullConfig.adaptiveViewAreas)
        assertEquals(listOf(MainViewArea(1920, 1080), MainViewArea(1284, 990)), fullConfig.adaptiveViewAreas)
    }

    @Test fun stableObservedSplitIsSavedButTransientResizeIsNot() {
        AdaptiveDisplayPreferences.setEnabled(activity, true)
        setField("hevcEnabled", false)
        setField("maximumDetectedWidthPixels", 1920)
        setField("maximumDetectedHeightPixels", 1080)
        startSession()
        val canvas = AdaptiveViewAreaHistory.Size(1920, 1080)
        applySize(1200, 990)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        applySize(1284, 990)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertEquals(listOf(AdaptiveViewAreaHistory.Size(1284, 990)), AdaptiveDisplayPreferences.splitAreas(activity, canvas))
        assertTrue(AdaptiveDisplayPreferences.splitAreas(activity, AdaptiveViewAreaHistory.Size(1080, 1920)).isEmpty())
    }

    @Test fun unchangedCurrentWindowIsRecordedAfterUpgrade() {
        AdaptiveDisplayPreferences.setEnabled(activity, true)
        setField("hevcEnabled", false)
        setField("maximumDetectedWidthPixels", 1920)
        setField("maximumDetectedHeightPixels", 1080)
        setField("activeDisplaySize", size(1284, 990))
        scheduleSize(1284, 990)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertEquals(listOf(AdaptiveViewAreaHistory.Size(1284, 990)),
            AdaptiveDisplayPreferences.splitAreas(activity, AdaptiveViewAreaHistory.Size(1920, 1080)))
    }

    private fun startSession(
        rotation: Int = Surface.ROTATION_0,
        windowWidth: Int = 1920,
        windowHeight: Int = 990,
        canvasWidth: Int = windowWidth,
        canvasHeight: Int = windowHeight,
        displayId: Int = 0,
    ): CarPlaySessionDisplay =
        CarPlaySessionDisplay(canvasWidth, canvasHeight, rotation, true, true, windowWidth, windowHeight, displayId).also {
            setField("activeDisplaySize", size(windowWidth, windowHeight))
            setField("sessionDisplay", it)
        }

    private fun keepLogs(): Int = ShadowLog.getLogsForTag("xcertplay-usb").count {
        it.msg.contains("keeping CarPlay session")
    }

    private fun size(width: Int, height: Int): Any = sizeClass
        .getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }.newInstance(width, height)

    private fun applySize(width: Int, height: Int) {
        activity.javaClass.getDeclaredMethod("applyDisplaySize", sizeClass)
            .apply { isAccessible = true }.invoke(activity, size(width, height))
    }

    private fun scheduleSize(width: Int, height: Int) {
        activity.javaClass.getDeclaredMethod("scheduleDisplaySize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, width, height)
    }

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        try {
            activity.javaClass.getDeclaredMethod("onHostTouch", View::class.java, MotionEvent::class.java)
                .apply { isAccessible = true }.invoke(activity, view, event)
        } finally {
            event.recycle()
        }
    }

    private fun getField(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)

    private fun setField(name: String, value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
