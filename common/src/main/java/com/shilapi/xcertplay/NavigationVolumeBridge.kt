package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/** BYD's private navigation stream is excluded by AudioManager's public minimum validator.
 * The vehicle's NAVI policy uses a zero minimum; maximum/current still come from AudioService.
 */
internal fun navigationBridgeMinimum(stream: Int, publicMinimum: (Int) -> Int): Int =
    if (stream == 14) 0 else publicMinimum(stream)

internal interface NavigationVolumePort {
    fun volume(stream: Int): Int
    fun minimum(stream: Int): Int
    fun maximum(stream: Int): Int
    fun setVolume(stream: Int, index: Int)
}

/** Compensates observed media changes; it cannot identify their physical input source. */
internal class NavigationVolumeBridgePolicy(
    private val port: NavigationVolumePort,
    private val canBridgeNow: () -> Boolean,
    private val report: (String) -> Unit = {},
    private val echoLifetimeMillis: Long = 1_000L,
) {
    private data class Echo(val previous: Int, val current: Int, val expiresAt: Long)
    private val echoes = ArrayDeque<Echo>()
    private var active = false
    private var eligible = false
    private var blocked = false
    val isBlocked: Boolean get() = blocked

    fun updateEligible(active: Boolean, eligible: Boolean) {
        if (!active) blocked = false
        else if (this.active && !eligible) blocked = true
        // Turning the switch on halfway through speech must not revive a blocked segment.
        if (active && !this.active && !eligible) blocked = true
        this.active = active
        this.eligible = eligible
    }

    fun onVolumeChanged(stream: Int, previous: Int, current: Int, now: Long) {
        if (stream != MEDIA) return
        val unresolvedEcho = echoes.any { it.expiresAt <= now }
        echoes.removeAll { it.expiresAt <= now }
        if (unresolvedEcho && active && eligible && !blocked) return fail("restore event timed out")
        val echo = echoes.firstOrNull { it.previous == previous && it.current == current }
        if (echo != null) {
            echoes.remove(echo)
            return
        }
        if (!active || !eligible || blocked || previous == current) return
        val delta = current.toLong() - previous.toLong()
        if (delta != -1L && delta != 1L) return
        try {
            if (!canBridgeNow()) return fail("eligibility changed")
            val mediaMin = port.minimum(MEDIA)
            val mediaMax = port.maximum(MEDIA)
            if (mediaMin > mediaMax || previous !in mediaMin..mediaMax || current !in mediaMin..mediaMax) {
                return fail("invalid media indices")
            }
            if (port.volume(MEDIA) != current) return fail("stale media event")
            val navigationMin = port.minimum(NAVIGATION)
            val navigationMax = port.maximum(NAVIGATION)
            val navigationBefore = port.volume(NAVIGATION)
            if (navigationMin > navigationMax || navigationBefore !in navigationMin..navigationMax) {
                return fail("invalid navigation indices")
            }
            // Broadcast values already use public volume steps, not AudioService's index * 10.
            val target = (navigationBefore.toLong() + delta)
                .coerceIn(navigationMin.toLong(), navigationMax.toLong()).toInt()
            if (!canBridgeNow()) return fail("eligibility changed before navigation write")
            port.setVolume(NAVIGATION, target)
            if (port.volume(NAVIGATION) != target) return fail("navigation write unconfirmed")
            if (!canBridgeNow()) return fail("eligibility changed before media restore")
            if (port.volume(MEDIA) != current) return fail("concurrent media change")
            // Record the precise inverse event before its broadcast can be delivered.
            val restore = Echo(current, previous, now + echoLifetimeMillis)
            echoes.addLast(restore)
            port.setVolume(MEDIA, previous)
            if (port.volume(MEDIA) != previous) return fail("media restore unconfirmed")
            log("navigation $navigationBefore->$target; media restored $current->$previous")
        } catch (error: Exception) {
            fail("write/read failed ${error.javaClass.simpleName}")
        }
    }

    fun fail(reason: String) {
        if (blocked) return
        blocked = true
        log("paused for this speech: $reason")
    }

    fun close() {
        active = false
        eligible = false
        blocked = true
        echoes.clear()
    }

    private fun log(message: String) {
        runCatching { report("Navigation volume bridge: $message") }
    }

    private companion object {
        const val MEDIA = 3
        const val NAVIGATION = 14
    }
}

/** A receiver scoped to one foreground host lifetime; close it before replacing the host or sink. */
internal class NavigationVolumeBridge(
    context: Context,
    canBridgeNow: () -> Boolean,
    report: (String) -> Unit = {},
) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(AudioManager::class.java)
    private var receiver: BroadcastReceiver? = null
    private var closed = false
    private val policy = NavigationVolumeBridgePolicy(object : NavigationVolumePort {
        override fun volume(stream: Int) = requireNotNull(manager).getStreamVolume(stream)
        override fun minimum(stream: Int) = navigationBridgeMinimum(stream) { requireNotNull(manager).getStreamMinVolume(it) }
        override fun maximum(stream: Int) = requireNotNull(manager).getStreamMaxVolume(stream)
        override fun setVolume(stream: Int, index: Int) = requireNotNull(manager).setStreamVolume(stream, index, 0)
    }, canBridgeNow = {
        manager?.mode == AudioManager.MODE_NORMAL && canBridgeNow()
    }, report = report)

    /** eligible must include the original switch, foreground ownership, attachment and route 14. */
    fun updateEligible(active: Boolean, eligible: Boolean) {
        if (closed) return
        policy.updateEligible(active, eligible && manager != null)
        if (!eligible || manager == null || receiver != null || policy.isBlocked) return
        val next = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (closed || receiver !== this || intent.action != ACTION) return
                if (!intent.hasExtra(STREAM) || !intent.hasExtra(PREVIOUS) || !intent.hasExtra(CURRENT)) return
                policy.onVolumeChanged(
                    intent.getIntExtra(STREAM, -1), intent.getIntExtra(PREVIOUS, -1),
                    intent.getIntExtra(CURRENT, -1), SystemClock.elapsedRealtime(),
                )
            }
        }
        receiver = next
        try {
            // AudioService sends as system UID. No third-party sender needs access to this receiver.
            ContextCompat.registerReceiver(context, next, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (error: Exception) {
            receiver = null
            policy.fail("receiver unavailable ${error.javaClass.simpleName}")
        }
    }

    /** Terminal: create a new bridge when the foreground host resumes again. */
    fun close() {
        closed = true
        val previous = receiver
        receiver = null
        if (previous != null) runCatching { context.unregisterReceiver(previous) }
        policy.close()
    }

    private companion object {
        const val ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val PREVIOUS = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
        const val CURRENT = "android.media.EXTRA_VOLUME_STREAM_VALUE"
    }
}
