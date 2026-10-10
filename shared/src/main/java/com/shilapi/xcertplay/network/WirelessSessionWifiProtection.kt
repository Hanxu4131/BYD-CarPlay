package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import java.io.Closeable

/** Locks follow the wireless session, rather than individual screens or activities. */
internal class WirelessSessionWifiProtection(
    private val sdkInt: Int,
    private val create: (Mode) -> Lease?,
    private val diagnostic: (String) -> Unit = {},
) : Closeable {
    enum class Mode(val value: Int) {
        HIGH_PERF(WifiManager.WIFI_MODE_FULL_HIGH_PERF),
        LOW_LATENCY(WifiManager.WIFI_MODE_FULL_LOW_LATENCY),
    }

    interface Lease {
        val isHeld: Boolean
        fun acquire()
        fun release()
    }

    private var owner: Any? = null
    private var closed = false
    private val leases = linkedMapOf<Mode, Lease>()

    @Synchronized fun sessionActive(session: Any, wireless: Boolean) {
        if (closed || !wireless || owner === session) return
        owner = session
        val modes = if (sdkInt >= 29) Mode.entries else listOf(Mode.HIGH_PERF)
        for (mode in modes) {
            if (closed || owner !== session) break
            try {
                val lease = leases[mode] ?: create(mode)?.also { leases[mode] = it }
                if (lease != null && !lease.isHeld) lease.acquire()
                report("Wi-Fi protection mode=$mode held=${lease?.isHeld ?: false}")
            } catch (failure: Exception) {
                report("Wi-Fi protection mode=$mode held=${runCatching { leases[mode]?.isHeld ?: false }.getOrNull() ?: "unknown"} failure=${failure.javaClass.simpleName}")
            }
        }
    }

    @Synchronized fun sessionEnded(session: Any) {
        if (owner !== session) return
        owner = null
        releaseLocks()
    }

    @Synchronized override fun close() {
        if (closed) {
            releaseLocks()
            return
        }
        closed = true
        owner = null
        releaseLocks()
    }

    private fun releaseLocks() {
        for ((mode, lease) in leases) {
            try {
                if (lease.isHeld) lease.release()
                report("Wi-Fi protection mode=$mode held=${lease.isHeld}")
            } catch (failure: Exception) {
                // Keep the handle so a later close can retry a ROM's failed release.
                report("Wi-Fi protection mode=$mode held=${runCatching { lease.isHeld }.getOrNull() ?: "unknown"} failure=${failure.javaClass.simpleName}")
            }
        }
    }

    private fun report(line: String) { runCatching { diagnostic(line) } }

    companion object {
        fun android(context: Context, diagnostic: (String) -> Unit): WirelessSessionWifiProtection {
            val app = context.applicationContext
            return WirelessSessionWifiProtection(Build.VERSION.SDK_INT, { mode ->
                val manager = app.getSystemService(WifiManager::class.java)
                manager?.let {
                    AndroidWifiLease(it.createWifiLock(mode.value, "DiPlay-${mode.name}"))
                }
            }, diagnostic)
        }
    }
}

/** Non-reference-counted handles prevent duplicate active notifications accumulating leases. */
internal class AndroidWifiLease(private val lock: WifiManager.WifiLock) : WirelessSessionWifiProtection.Lease {
    init { lock.setReferenceCounted(false) }
    override val isHeld: Boolean get() = lock.isHeld
    override fun acquire() = lock.acquire()
    override fun release() = lock.release()
}
