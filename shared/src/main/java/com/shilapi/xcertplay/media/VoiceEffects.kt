package com.shilapi.xcertplay.media

import java.io.Closeable

/** Optional recorder effects: a vendor failure must not prevent microphone capture. */
internal class VoiceEffects<T>(
    private val enable: (T) -> Boolean,
    private val release: (T) -> Unit,
    private val report: (String, RuntimeException?) -> Unit,
) : Closeable {
    private val active = ArrayList<T>()

    @Synchronized
    fun add(name: String, create: () -> T?) {
        var effect: T? = null
        try {
            effect = create()
            if (effect != null && enable(effect)) {
                active.add(effect)
                report("microphone effect=$name enabled=true", null)
                return
            }
            report("microphone effect=$name unavailable", null)
        } catch (error: RuntimeException) {
            report("microphone effect=$name unavailable; continuing without it", error)
        }
        effect?.let(::releaseSafely)
    }

    @Synchronized
    override fun close() {
        val effects = active.toList()
        active.clear()
        effects.forEach(::releaseSafely)
    }

    private fun releaseSafely(effect: T) {
        try {
            release(effect)
        } catch (error: RuntimeException) {
            report("microphone effect release failed", error)
        }
    }
}
