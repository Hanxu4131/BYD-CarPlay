package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The surfaces that show a copy of the dashboard map (CarPlay stream 111) outside the dashboard,
 * such as the centre card ([CARD]) and maps embedded by launchers ("launcher:<n>"). The CarPlay screen hands them to its media sink, now and after
 * every reconnect. Main thread.
 */
internal object MapMirrors {
    const val CARD = "card"

    /** The dashboard stream's shape (1920x720, sent scaled to 1600x600 by default). */
    const val STREAM_ASPECT = 8.0 / 3

    private val main = Handler(Looper.getMainLooper())
    private val surfaces = LinkedHashMap<String, Surface>()
    private val readySurfaces = LinkedHashMap<String, Surface>()
    private val presentedSurfaces = LinkedHashMap<String, Surface>()
    private val presentedListeners = CopyOnWriteArraySet<(String, Surface?, Boolean) -> Unit>()
    private val generations = HashMap<String, Long>()
    private val presentationGenerations = HashMap<String, Long>()
    private val readinessListeners = CopyOnWriteArraySet<(String, Surface?, Boolean) -> Unit>()
    private val streamListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    /** Set by the CarPlay screen: applies one mirror to its current media sink. */
    var sink: ((String, Surface?) -> Unit)? = null

    /** Preserve readiness only when a host adopts the same live renderer; new sinks reset it. */
    fun reapply(preserveReadiness: Boolean = false) {
        val apply = sink ?: return
        surfaces.forEach { (key, surface) ->
            val retained = preserveReadiness && surface.isValid &&
                (readySurfaces[key] === surface || presentedSurfaces[key] === surface)
            // Reapplying the same surface would close its existing mirror decoder.
            if (retained) return@forEach
            resetReadiness(key, surface)
            apply(key, surface)
        }
    }

    /** Called when the set of mirrors changes, so the dashboard map pause can stand aside. */
    var onChanged: (() -> Unit)? = null

    /** Whether the iPhone streams the dashboard map right now. */
    var streamActive = false
        private set

    fun set(key: String, surface: Surface?) {
        if (surface == null) {
            if (surfaces.remove(key) == null) return
        } else {
            surfaces[key] = surface
        }
        resetReadiness(key, surface)
        sink?.invoke(key, surface)
        onChanged?.invoke()
    }

    /** Ready only after the current decoder has rendered into this exact Surface. Main thread. */
    fun isReady(key: String, surface: Surface): Boolean = readySurfaces[key] === surface
    fun isPresented(key: String, surface: Surface): Boolean = presentedSurfaces[key] === surface
    fun addPresentedListener(listener: (String, Surface?, Boolean) -> Unit) { presentedListeners.add(listener) }
    fun removePresentedListener(listener: (String, Surface?, Boolean) -> Unit) { presentedListeners.remove(listener) }
    /** Only the MediaCodec OnFrameRendered path calls this, never the decoded-output fallback. */
    fun framePresentedCallback(key: String, surface: Surface): () -> Unit {
        val generation = presentationGenerations[key]
        return {
            val present = {
                if (presentationGenerations[key] == generation && surfaces[key] === surface && surface.isValid &&
                    presentedSurfaces[key] !== surface) {
                    presentedSurfaces[key] = surface
                    presentedListeners.forEach { it(key, surface, true) }
                }
            }
            if (Looper.myLooper() === Looper.getMainLooper()) present() else main.post(present)
            Unit
        }
    }

    fun addReadinessListener(listener: (String, Surface?, Boolean) -> Unit) { readinessListeners.add(listener) }

    fun removeReadinessListener(listener: (String, Surface?, Boolean) -> Unit) { readinessListeners.remove(listener) }

    private fun resetReadiness(key: String, surface: Surface?) {
        generations[key] = (generations[key] ?: 0L) + 1L
        presentationGenerations[key] = (presentationGenerations[key] ?: 0L) + 1L
        readySurfaces.remove(key)
        presentedSurfaces.remove(key)
        presentedListeners.forEach { it(key, surface, false) }
        readinessListeners.forEach { it(key, surface, false) }
    }

    /** Capture the current attachment; queued callbacks from previous sessions cannot mark it ready. */
    fun frameRenderedCallback(key: String, surface: Surface): () -> Unit {
        val generation = generations[key]
        return {
            val markReady = {
                if (generations[key] == generation && surfaces[key] === surface && surface.isValid &&
                    readySurfaces[key] !== surface) {
                    readySurfaces[key] = surface
                    readinessListeners.forEach { it(key, surface, true) }
                }
            }
            if (Looper.myLooper() === Looper.getMainLooper()) markReady() else main.post(markReady)
            Unit
        }
    }

    val any: Boolean get() = surfaces.isNotEmpty()

    /** Whether a launcher shows the map (see [MapEmbedService]), so the centre card is not needed. */
    val launcherShowsMap: Boolean get() = surfaces.keys.any { it != CARD && it != LegacyClusterMap.MIRROR }

    fun setStreamActive(active: Boolean) {
        main.post {
            if (streamActive == active) return@post
            streamActive = active
            if (!active) {
                readySurfaces.clear()
                presentedSurfaces.clear()
                surfaces.keys.forEach { key -> presentationGenerations[key] = (presentationGenerations[key] ?: 0L) + 1L }
                surfaces.forEach { (key, surface) -> presentedListeners.forEach { it(key, surface, false) } }
                surfaces.forEach { (key, surface) -> readinessListeners.forEach { it(key, surface, false) } }
            }
            streamListeners.forEach { it(active) }
        }
    }

    fun addStreamListener(listener: (Boolean) -> Unit) { streamListeners.add(listener) }

    fun removeStreamListener(listener: (Boolean) -> Unit) { streamListeners.remove(listener) }
}
