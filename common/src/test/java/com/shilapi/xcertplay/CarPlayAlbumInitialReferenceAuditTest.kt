package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.media.AmbientMusicController
import com.shilapi.xcertplay.media.AmbientMusicSettings
import com.shilapi.xcertplay.media.AmbientColorSource
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayAlbumInitialReferenceAuditTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<CarPlayController>()
    private lateinit var owner: CarPlayController

    @Before fun setup() {
        // No sink/renderer is opened and the lamp is disabled; this is metadata-only.
        ReflectionHelpers.setField(AmbientMusicController, "settings",
            AmbientMusicSettings.Values(enabled = false, colorSource = AmbientColorSource.ALBUM))
        owner = controller()
        CarPlayMediaKeys.attach(app, owner)
        flushAmbient()
        assertNull(albumColor())
    }

    @After fun cleanup() {
        controllers.forEach(CarPlayMediaKeys::detach)
        controllers.forEach { it.close(); it.awaitClosed(1000) }
        flushAmbient()
        assertNull(albumColor())
    }

    @Test fun coverBeforeReferenceIsCachedAndAppliedWhenReferenceArrives() {
        decoded(17, 8)
        assertNull(albumColor())
        metadata(CarPlayNowPlaying(title = "song", artworkTransferId = 17, playing = true))
        assertEquals(8, albumColor())
    }

    @Test fun referenceBeforeCoverUsesFallbackThenAppliesDecodedColor() {
        metadata(CarPlayNowPlaying(title = "song", artworkTransferId = 17, playing = true))
        assertNull(albumColor())
        decoded(17, 8)
        assertEquals(8, albumColor())
    }

    @Test fun initialTitleAndDecodedCoverWithoutReferenceRemainFallbackUntilReferenceArrives() {
        metadata(CarPlayNowPlaying(title = "song", playing = true))
        decoded(17, 8)
        assertNull(albumColor())
        // Repeated playback/title/elapsed updates without the reference cannot associate the cover.
        metadata(CarPlayNowPlaying(title = "song", elapsedMillis = 3000, playing = true))
        metadata(CarPlayNowPlaying(title = "song", elapsedMillis = 6000, playing = true))
        assertNull(albumColor())
        assertEquals(8, ReflectionHelpers.getField<Map<Int, Int?>>(CarPlayMediaKeys, "ambientColorCache")[17])
        metadata(CarPlayNowPlaying(title = "song", artworkTransferId = 17, elapsedMillis = 7000, playing = true))
        assertEquals(8, albumColor())
    }

    @Test fun initialMissingReferenceRecoversAfterNextTrackReferenceAndCover() {
        metadata(CarPlayNowPlaying(title = "song", playing = true))
        decoded(17, 8)
        assertNull(albumColor())
        metadata(CarPlayNowPlaying(title = "next song", artworkTransferId = 18, playing = true))
        assertNull(albumColor())
        decoded(18, 15)
        assertEquals(15, albumColor())
        // Previous/next also recovers directly from cached artwork when its reference is present.
        metadata(CarPlayNowPlaying(title = "song", artworkTransferId = 17, playing = true))
        assertEquals(8, albumColor())
    }

    @Test fun initialReferenceWithoutDecodedCoverStaysFallbackUntilCoverArrivesOrTrackChanges() {
        metadata(CarPlayNowPlaying(title = "song", artworkTransferId = 17, playing = true))
        metadata(CarPlayNowPlaying(title = "song", artworkTransferId = 17, elapsedMillis = 6000, playing = true))
        assertNull(albumColor())
        decoded(18, 15)
        assertNull(albumColor())
        metadata(CarPlayNowPlaying(title = "next song", artworkTransferId = 18, playing = true))
        assertEquals(15, albumColor())
    }

    @Test fun controllerReplayRetainsOnlyOneBoundedCoverAndRequiresMatchingReference() {
        fun transfer(id: Int, size: Int) {
            val type = com.shilapi.xcertplay.transport.Iap2ArtworkTransfer::class.java
            CarPlayController::class.java.getDeclaredMethod("onArtworkTransfer", type)
                .apply { isAccessible = true }
                .invoke(owner, com.shilapi.xcertplay.transport.Iap2ArtworkTransfer(id, ByteArray(size)))
        }
        transfer(17, 32)
        assertNull(owner.matchingArtworkSnapshot(null))
        assertNull(owner.matchingArtworkSnapshot(18))
        assertEquals(32, owner.matchingArtworkSnapshot(17)?.bytes?.size)
        transfer(18, 64)
        assertNull(owner.matchingArtworkSnapshot(17))
        assertEquals(64, owner.matchingArtworkSnapshot(18)?.bytes?.size)
        transfer(19, 1024 * 1024 + 1)
        assertNull(owner.matchingArtworkSnapshot(19))
        assertNull(owner.matchingArtworkSnapshot(18))
        transfer(20, 32)
        owner.close()
        assertNull(owner.matchingArtworkSnapshot(20))
    }

    private fun metadata(update: CarPlayNowPlaying) {
        CarPlayMediaKeys::class.java.getDeclaredMethod("onNowPlayingChanged", CarPlayController::class.java,
            CarPlayNowPlaying::class.java).apply { isAccessible = true }.invoke(CarPlayMediaKeys, owner, update)
        flushAmbient()
    }

    private fun decoded(id: Int, color: Int) {
        val type = Class.forName("com.shilapi.xcertplay.CarPlayMediaKeys\$AmbientArtwork")
        val result = type.getDeclaredConstructor(Bitmap::class.java, Integer::class.java).apply { isAccessible = true }
            .newInstance(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888), color)
        val session = ReflectionHelpers.getField<Any>(CarPlayMediaKeys, "artworkOwner")
        CarPlayMediaKeys::class.java.getDeclaredMethod("onArtworkDecoded", Any::class.java, Int::class.javaPrimitiveType,
            type).apply { isAccessible = true }.invoke(CarPlayMediaKeys, session, id, result)
        flushAmbient()
    }

    private fun albumColor(): Int? = ReflectionHelpers.getField(AmbientMusicController, "albumColor")
    private fun flushAmbient() {
        ReflectionHelpers.getField<ScheduledExecutorService>(AmbientMusicController, "executor")
            .submit {}.get(3, TimeUnit.SECONDS)
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

}
