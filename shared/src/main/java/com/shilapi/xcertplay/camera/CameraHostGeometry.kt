package com.shilapi.xcertplay.camera

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/** Host window bounds in physical pixels, independent of the encoded CarPlay canvas size. */
data class CameraHostWindowGeometry(
    val hostWidthPx: Int,
    val hostHeightPx: Int,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val inMultiWindowMode: Boolean,
    val forceFullscreen: Boolean = false,
)

data class CameraHostGeometrySnapshot(
    val ownerToken: Long,
    val window: CameraHostWindowGeometry?,
    val active: Boolean,
    val split: Boolean?,
    val revision: Long,
)

/**
 * Shared host-window split state for camera visibility. Front views may activate only when
 * [currentSplit] is true. Null means no current host or geometry is not yet measurable.
 */
object CameraHostGeometry {
    private val nextOwner = AtomicLong(0L)
    private val revision = AtomicLong(0L)
    private val state = AtomicReference<CameraHostGeometrySnapshot?>(null)
    private val listeners = CopyOnWriteArrayList<(Boolean?) -> Unit>()

    /** Starts a new host generation and invalidates any measurements from the previous host. */
    fun attachHost(): Long {
        val token = nextOwner.incrementAndGet()
        state.set(CameraHostGeometrySnapshot(token, null, active = false, split = null, revision.incrementAndGet()))
        notifySplit(null)
        return token
    }

    /** Updates the current host only. Late callbacks from an older Activity are ignored. */
    fun updateHost(token: Long, window: CameraHostWindowGeometry): Boolean {
        while (true) {
            val old = state.get() ?: return false
            if (old.ownerToken != token) return false
            val split = if (old.active) classify(window) else false
            val updated = old.copy(window = window, split = split, revision = revision.incrementAndGet())
            if (state.compareAndSet(old, updated)) {
                if (old.split != split) notifySplit(split)
                return true
            }
        }
    }

    /** A paused host cannot keep a stale split overlay visible while another screen is shown. */
    fun setHostActive(token: Long, active: Boolean): Boolean {
        while (true) {
            val old = state.get() ?: return false
            if (old.ownerToken != token) return false
            // A resumed host must be measured again before fronts may activate.
            val split = if (!active) false else null
            val updated = old.copy(active = active, split = split, revision = revision.incrementAndGet())
            if (state.compareAndSet(old, updated)) {
                if (old.split != split) notifySplit(split)
                return true
            }
        }
    }

    /** Clears state only if the caller still owns the current host generation. */
    fun detachHost(token: Long): Boolean {
        while (true) {
            val old = state.get() ?: return false
            if (old.ownerToken != token) return false
            if (state.compareAndSet(old, null)) {
                notifySplit(null)
                return true
            }
        }
    }

    fun currentSplit(): Boolean? = state.get()?.split

    fun currentSnapshot(): CameraHostGeometrySnapshot? = state.get()

    /** Subscribers receive the current value immediately, then each actual split-state change. */
    fun subscribe(listener: (Boolean?) -> Unit): AutoCloseable {
        listeners += listener
        runCatching { listener(currentSplit()) }
        return AutoCloseable { listeners.remove(listener) }
    }

    /** Pure classifier; system bars/insets are tolerated, encoded-video dimensions are unused. */
    fun classify(window: CameraHostWindowGeometry): Boolean? {
        if (window.forceFullscreen) return false
        if (window.hostWidthPx <= 0 || window.hostHeightPx <= 0 ||
            window.displayWidthPx <= 0 || window.displayHeightPx <= 0) return null
        val hostLong = max(window.hostWidthPx, window.hostHeightPx).toFloat()
        val hostShort = min(window.hostWidthPx, window.hostHeightPx).toFloat()
        val displayLong = max(window.displayWidthPx, window.displayHeightPx).toFloat()
        val displayShort = min(window.displayWidthPx, window.displayHeightPx).toFloat()
        val coversDisplay = hostLong / displayLong >= FULL_WINDOW_COVERAGE &&
            hostShort / displayShort >= FULL_WINDOW_COVERAGE
        // Windowing-mode flags are advisory: embedded hosts can report fullscreen while
        // occupying only part of the physical display. Actual bounds take precedence.
        if (coversDisplay) return false
        if (window.inMultiWindowMode) return true
        return !coversDisplay
    }

    private fun notifySplit(split: Boolean?) {
        listeners.forEach { listener -> runCatching { listener(split) } }
    }

    private const val FULL_WINDOW_COVERAGE = 0.88f
}
