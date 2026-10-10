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

    /** Keeps another working effect only after this handle is confirmed off. */
    @Synchronized
    fun configureCapture(name: String, requested: Boolean, create: () -> T?): VoiceEffectState {
        val effect = try {
            create()
        } catch (error: RuntimeException) {
            report("microphone effect=$name enabled=unknown reason=create failed", error)
            return VoiceEffectState.UNCONFIRMED
        }
        if (effect == null) {
            report("microphone effect=$name enabled=unknown reason=unavailable", null)
            return VoiceEffectState.UNCONFIRMED
        }
        val configured = try {
            configure(effect, requested)
        } catch (error: RuntimeException) {
            report("microphone effect=$name requested=$requested configuration failed", error)
            false
        }
        if (configured) {
            active.add(name to effect)
            report("microphone effect=$name enabled=$requested", null)
            return if (requested) VoiceEffectState.ENABLED else VoiceEffectState.DISABLED
        }
        val disabled = try {
            configure(effect, false)
        } catch (error: RuntimeException) {
            report("microphone effect=$name disable confirmation failed", error)
            false
        }
        if (disabled) {
            // Releasing a disabled handle can restore vendor defaults during the same session.
            active.add(name to effect)
            report("microphone effect=$name enabled=false requested=$requested disabledConfirmed=true", null)
            return VoiceEffectState.DISABLED
        }
        report("microphone effect=$name enabled=unknown disabledConfirmed=false", null)
        releaseSafely(effect)
        return VoiceEffectState.UNCONFIRMED
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
