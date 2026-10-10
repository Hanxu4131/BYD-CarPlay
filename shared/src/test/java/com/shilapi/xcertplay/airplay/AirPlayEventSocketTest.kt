package com.shilapi.xcertplay.airplay

import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AirPlayEventSocketTest {
    @Test
    fun acceptedEventSocketDisablesNagleBeforeAuthenticationOrDelivery() {
        var noDelay = false
        var closedWithNoDelay = false
        val accepted = object : Socket() {
            override fun setSoLinger(on: Boolean, linger: Int) {}
            override fun setTcpNoDelay(on: Boolean) { noDelay = on }
            override fun getTcpNoDelay(): Boolean = noDelay
            override fun close() { closedWithNoDelay = noDelay }
        }
        val server = object : ServerSocket() {
            override fun accept(): Socket = accepted
        }
        val session = AirPlaySession(socket = Socket(), config = AirPlayConfig(
            deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0", main = AirPlayDisplayConfig(800, 480)),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {}, media = object : AirPlayMediaHandler {})
        try {
            AirPlaySession::class.java.getDeclaredMethod("acceptEvent", ServerSocket::class.java)
                .apply { isAccessible = true }.invoke(session, server)
            assertTrue(noDelay)
            assertTrue(closedWithNoDelay)
        } finally { session.close(); server.close() }
    }
}
