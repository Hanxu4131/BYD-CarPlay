package com.shilapi.xcertplay.media

/** Android's standard AEC/NS APIs expose an on/off state, with no portable strength levels. */
data class MicrophoneProcessing(
    val noiseSuppression: Boolean = false,
    val echoCancellation: Boolean = false,
    /** A device adaptation can bypass effects known to silence its recorder. */
    val systemEffectsAllowed: Boolean = true,
    val preferMicSource: Boolean = false,
)

internal data class MicrophoneCapturePolicy(
    val useMicSource: Boolean,
    val configureSystemEffects: Boolean,
    val noiseSuppression: Boolean,
    val echoCancellation: Boolean,
) {
    companion object {
        fun from(processing: MicrophoneProcessing) = MicrophoneCapturePolicy(
            useMicSource = !processing.systemEffectsAllowed || processing.preferMicSource,
            configureSystemEffects = processing.systemEffectsAllowed &&
                (!processing.preferMicSource || processing.noiseSuppression || processing.echoCancellation),
            noiseSuppression = processing.systemEffectsAllowed && processing.noiseSuppression,
            echoCancellation = processing.systemEffectsAllowed && processing.echoCancellation,
        )
    }
}
