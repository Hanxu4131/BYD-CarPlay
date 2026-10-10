package com.shilapi.xcertplay.media

internal enum class VoiceEffectState { ENABLED, DISABLED, UNCONFIRMED }

/** A failed optional effect cannot disable a working one when its own off state is known. */
internal data class MicrophoneEffectStartup(
    val aecRequested: Boolean,
    val nsRequested: Boolean,
    val aec: VoiceEffectState,
    val ns: VoiceEffectState,
) {
    val needsRawFallback: Boolean get() {
        val failed = (aecRequested && aec != VoiceEffectState.ENABLED) ||
            (nsRequested && ns != VoiceEffectState.ENABLED)
        return failed && (!hasEnabledEffect ||
            (aecRequested && aec == VoiceEffectState.UNCONFIRMED) ||
            (nsRequested && ns == VoiceEffectState.UNCONFIRMED))
    }
    val hasEnabledEffect: Boolean get() = aec == VoiceEffectState.ENABLED || ns == VoiceEffectState.ENABLED
    val partial: Boolean get() = hasEnabledEffect &&
        ((aecRequested && aec != VoiceEffectState.ENABLED) || (nsRequested && ns != VoiceEffectState.ENABLED))
}
