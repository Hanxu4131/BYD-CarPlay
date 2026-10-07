package com.shilapi.xcertplay.media

import java.io.Closeable

/** Optional recorder effects: a vendor failure must not prevent microphone capture. */
internal class VoiceEffects<T>(
    private val configure: (T, Boolean) -> Boolean,
    private val release: (T) -> Unit,
    private val report: (String, RuntimeException?) -> Unit,
) : Closeable {
    private val active = ArrayList<Pair<String, T>>()

    @Synchronized
    fun add(name: String, enabled: Boolean = true, create: () -> T?): Boolean {
        var effect: T? = null
        try {
            effect = create()
            if (effect != null && configure(effect, enabled)) {
                active.add(name to effect)
                report("microphone effect=$name enabled=$enabled", null)
                return true
            }
            report("microphone effect=$name unavailable", null)
        } catch (error: RuntimeException) {
            report("microphone effect=$name unavailable; continuing without it", error)
        }
        effect?.let(::releaseSafely)
        return false
    }

    @Synchronized
    fun disableAll(): Boolean {
        var success = true
        active.forEach { (name, effect) ->
            try {
                val disabled = configure(effect, false)
                report("microphone effect=$name fallbackDisabled=$disabled", null)
                if (!disabled) success = false
            } catch (error: RuntimeException) {
                success = false
                report("microphone effect=$name fallback disable failed", error)
            }
        }
        return success
    }

    @Synchronized
    override fun close() {
        val effects = active.toList()
        active.clear()
        effects.forEach { releaseSafely(it.second) }
    }

    private fun releaseSafely(effect: T) {
        try {
            release(effect)
        } catch (error: RuntimeException) {
            report("microphone effect release failed", error)
        }
    }
}
