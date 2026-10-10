package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayMediaFocusForwardingTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<CarPlayController>()
    private val sinks = mutableListOf<AndroidMediaSink>()
    private val tracks = mutableListOf<AudioTrack>()
    private val focusEvents = mutableMapOf<AndroidMediaSink, MutableList<Int>>()
    private val ownerLocksHeld = mutableListOf<Boolean>()

    @Before fun resetFocusResponse() {
        shadowOf(app.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    @After fun cleanup() {
        controllers.forEach(CarPlayMediaKeys::detach)
        controllers.forEach { it.close(); it.awaitClosed(1000) }
        sinks.forEach(AndroidMediaSink::close)
        tracks.forEach(AudioTrack::release)
        CarPlayBackgroundSession.clear()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(ownerLocksHeld.any { it })
    }

    @Test fun currentMediaKeyFocusReachesMatchingSinkOutsideOwnerLocks() {
        val controller = controller()
        val sink = sink()
        val listener = start(controller, sink)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1, events(sink).count { it == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT })
        assertEquals(1, events(sink).count { it == AudioManager.AUDIOFOCUS_GAIN })
    }

    @Test fun oldControllerAndOldRequestCallbacksCannotAffectReplacementSink() {
        val firstController = controller()
        val firstSink = sink()
        val oldListener = start(firstController, firstSink)
        val secondController = controller()
        val secondSink = sink()
        val currentListener = start(secondController, secondSink)
        oldListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(events(firstSink).isEmpty())
        assertTrue(events(secondSink).isEmpty())
        currentListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(1, events(secondSink).count { it == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT })
        CarPlayMediaKeys.detach(secondController)
        val replacement = start(secondController, secondSink)
        events(secondSink).clear()
        currentListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(events(secondSink).isEmpty())
        replacement.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1, events(secondSink).count { it == AudioManager.AUDIOFOCUS_GAIN })
    }

    @Test fun backgroundSessionMismatchIsNotForwarded() {
        val controller = controller()
        val sink = sink()
        val listener = start(controller, sink)
        CarPlayBackgroundSession.clear()
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(events(sink).isEmpty())
    }

    @Test fun immediateStartGrantIsForwardedWithoutWaitingForPlatformGainCallback() {
        val controller = controller()
        val sink = sink()
        start(controller, sink, clearInitialGrant = false)
        assertEquals(1, events(sink).count { it == AudioManager.AUDIOFOCUS_GAIN })
    }

    @Test fun immediateRegrantRestoresFocusButRejectedRequestDoesNot() {
        val controller = controller()
        val sink = sink()
        val listener = start(controller, sink)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        events(sink).clear()
        val audio = shadowOf(app.getSystemService(AudioManager::class.java))
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(events(sink).contains(AudioManager.AUDIOFOCUS_GAIN))
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, events(sink).count { it == AudioManager.AUDIOFOCUS_GAIN })
    }

    @Test fun queuedRealLossInvalidatesEarlierImmediateGrantBeforeItCanRestoreAudio() {
        val controller = controller()
        val sink = sink()
        val listener = start(controller, sink)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        events(sink).clear()
        // Deliver a real loss ahead of the synthetic grant by processing it while the successful
        // request dispatch is still pending on the main handler.
        CarPlayMediaKeys::class.java.getDeclaredMethod("regainFocusLocked").apply { isAccessible = true }
            .let { method -> synchronized(CarPlayMediaKeys) { method.invoke(CarPlayMediaKeys) } }
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, events(sink).count { it == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT })
        assertFalse(events(sink).contains(AudioManager.AUDIOFOCUS_GAIN))
    }

    @Test fun queuedImmediateGrantCannotRestoreAReleasedRequest() {
        val controller = controller()
        val sink = sink()
        val listener = start(controller, sink)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        events(sink).clear()
        CarPlayMediaKeys::class.java.getDeclaredMethod("regainFocusLocked").apply { isAccessible = true }
            .let { method -> synchronized(CarPlayMediaKeys) { method.invoke(CarPlayMediaKeys) } }
        CarPlayMediaKeys.detach(controller)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(events(sink).contains(AudioManager.AUDIOFOCUS_GAIN))
    }

    private fun events(sink: AndroidMediaSink) = focusEvents.getValue(sink)

    private fun sink(): AndroidMediaSink {
        val events = mutableListOf<Int>()
        val sink = AndroidMediaSink(context = app, audioFocusEnabled = true, onAudioDiagnostic = { line ->
            if (line.startsWith("Audio: focus change=")) {
                ownerLocksHeld += Thread.holdsLock(CarPlayMediaKeys) || Thread.holdsLock(CarPlayBackgroundSession)
                events += line.substringAfter("change=").substringBefore(' ').toInt()
            }
        }).also(sinks::add)
        focusEvents[sink] = events
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val track = AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(44100).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(4096).build().also(tracks::add)
        // The coordinator is internal to shared; reflect only to supply a real active media track.
        val coordinator = sink.javaClass.getDeclaredField("audioFocusCoordinator")
            .apply { isAccessible = true }.get(sink)
        val channelClass = Class.forName("com.shilapi.xcertplay.media.AudioChannel")
        val media = channelClass.enumConstants.single { (it as Enum<*>).name == "MEDIA" }
        coordinator.javaClass.getDeclaredMethod("acquire", AudioTrack::class.java, channelClass, AudioAttributes::class.java)
            .apply { isAccessible = true }.invoke(coordinator, track, media, attributes)
        return sink
    }

    private fun controller(): CarPlayController {
        val noVendorContext = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean = false
        }
        val config = AirPlayConfig(deviceName = "focus-test", deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01", sourceVersion = "1",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480))
        return CarPlayController(noVendorContext,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            config, AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {},
            object : AirPlayMediaHandler {}, {}).also(controllers::add)
    }

    private fun start(controller: CarPlayController, sink: AndroidMediaSink,
        clearInitialGrant: Boolean = true): AudioManager.OnAudioFocusChangeListener {
        CarPlayBackgroundSession.store(controller, sink, 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) {}
        CarPlayMediaKeys.attach(app, controller)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        if (clearInitialGrant) events(sink).clear()
        val request = ReflectionHelpers.getField<AudioFocusRequest>(CarPlayMediaKeys, "focusRequest")
        return ReflectionHelpers.callInstanceMethod(request, "getOnAudioFocusChangeListener")
    }
}
