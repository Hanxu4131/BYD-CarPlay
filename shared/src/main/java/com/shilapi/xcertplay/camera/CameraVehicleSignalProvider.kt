package com.shilapi.xcertplay.camera

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only vehicle signal subscription. Construction starts polling; close releases its shell worker. */
class CameraVehicleSignalProvider(
    context: Context,
    private val onSignals: (CameraVehicleSignals) -> Unit,
    private val encoding: CameraVehicleSignalEncoding = CameraVehicleSignalNormalizer.TangAutomaticGearEncoding,
) : AutoCloseable {
    private val app = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "camera-vehicle-signals").apply { isDaemon = true } }
    private var adb: LocalAdb? = null
    private var stream: LocalAdb.InteractiveShell? = null
    private var token: String? = null
    private var retryAtNanos = 0L

    init { executor.scheduleWithFixedDelay(::poll, 0, POLL_MS, TimeUnit.MILLISECONDS) }

    private fun poll() {
        if (closed.get()) return
        try {
            if (!ensureWorker()) {
                publishUnknown()
                return
            }
            val response = exchange("read", 1500)?.takeIf { it.startsWith("raw=") }
                ?: error("signal-read-failed")
            val raw = parseRaw(response.removePrefix("raw="))
            onSignals(CameraVehicleSignalNormalizer.normalize(raw, encoding))
        } catch (t: Throwable) {
            Log.w(TAG, "vehicle signal read unavailable: ${t.javaClass.simpleName}")
            publishUnknown()
            closeTransport()
            retryAtNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(RETRY_SECONDS)
        }
    }

    private fun ensureWorker(): Boolean {
        if (stream != null) return true
        if (System.nanoTime() < retryAtNanos) return false
        val client = LocalAdb(AdbKeys.load(app))
        adb = client
        if (client.connect(mayAsk = false) != LocalAdb.Access.READY) {
            closeTransport()
            retryAtNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(RETRY_SECONDS)
            return false
        }
        val nextToken = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        token = nextToken
        val apk = "'" + app.applicationInfo.sourceDir.replace("'", "'\\''") + "'"
        stream = client.openShell("CLASSPATH=$apk app_process /system/bin ${CameraVehicleSignalWorker::class.java.name} --stdin $nextToken")
        if (stream == null || exchange("ping", 8000) != "ready") {
            closeTransport()
            retryAtNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(RETRY_SECONDS)
            return false
        }
        return true
    }

    private fun parseRaw(text: String): CameraVehicleRawSignals {
        val f = text.split(',')
        require(f.size == 6) { "signal-field-count" }
        fun intAt(index: Int) = f[index].takeIf { it != "~" }?.toIntOrNull()
        return CameraVehicleRawSignals(
            automaticGear = intAt(0), brakeDepth = intAt(1), globalTurnState = intAt(2),
            leftTurnState = intAt(3), rightTurnState = intAt(4),
            steeringValue = f[5].takeIf { it != "~" }?.toDoubleOrNull(),
        )
    }

    private fun exchange(command: String, timeout: Int = 1200): String? = token?.let { nonce ->
        stream?.exchangeBounded("$nonce $command", timeout)
    }

    private fun publishUnknown() {
        runCatching { onSignals(CameraVehicleSignals(null, null, null, null, null, null, null, null)) }
    }

    private fun closeTransport() {
        val oldStream = stream; stream = null
        val oldAdb = adb; adb = null; token = null
        runCatching { oldStream?.close() }
        runCatching { oldAdb?.close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.execute {
            runCatching { exchange("close", 500) }
            closeTransport()
            executor.shutdown()
        }
    }

    companion object {
        private const val TAG = "DiPlay-CameraSignals"
        private const val POLL_MS = 200L
        private const val RETRY_SECONDS = 15L
    }
}
