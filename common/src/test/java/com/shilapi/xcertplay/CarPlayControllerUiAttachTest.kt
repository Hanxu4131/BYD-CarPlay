package com.shilapi.xcertplay

import android.os.Looper
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Socket
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayControllerUiAttachTest {
    private lateinit var controller: CarPlayController
    private lateinit var session: AirPlaySession
    private val config = AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:01", sourceVersion = "1",
        main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480))

    @Before fun setUp() {
        // Keep vendor workers away from Robolectric's synthetic service callback, including after reset.
        val noVendorContext = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean = false
        }
        val identity = AirPlayIdentity.generate()
        val pairings = PairingStore()
        val media = object : AirPlayMediaHandler {}
        session = AirPlaySession(Socket(), config, identity, pairings, null,
            object : AirPlaySessionListener {}, media)
        controller = CarPlayController(noVendorContext,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            config, identity, pairings, object : AirPlaySessionListener {}, media, {})
        activeSession(session)
    }

    @After fun tearDown() {
        controller.close()
        controller.awaitClosed(1000)
        session.close()
    }

    @Test fun returningUiReceivesTheSessionThatRemainedConnected() {
        val seen = mutableListOf<AirPlaySession>()
        controller.attachUi(listener(seen), {})
        assertTrue(seen.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(session), seen)
    }

    @Test fun aSupersededUiDoesNotReceiveTheReplay() {
        val old = mutableListOf<AirPlaySession>()
        val current = mutableListOf<AirPlaySession>()
        controller.attachUi(listener(old), {})
        controller.attachUi(listener(current), {})
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(old.isEmpty())
        assertEquals(listOf(session), current)
    }

    @Test fun aSessionThatEndedBeforeDeliveryIsNotReplayed() {
        val seen = mutableListOf<AirPlaySession>()
        controller.attachUi(listener(seen), {})
        activeSession(null)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(seen.isEmpty())
    }

    @Test fun aClosedControllerDoesNotReplayItsSession() {
        val seen = mutableListOf<AirPlaySession>()
        controller.attachUi(listener(seen), {})
        controller.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(seen.isEmpty())
    }

    private fun listener(seen: MutableList<AirPlaySession>) = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) { seen += session }
    }

    private fun activeSession(value: AirPlaySession?) {
        controller.javaClass.getDeclaredField("activeSession").apply { isAccessible = true }
            .set(controller, value)
    }
}
