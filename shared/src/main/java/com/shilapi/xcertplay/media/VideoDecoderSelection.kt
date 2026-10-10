package com.shilapi.xcertplay.media

internal data class VideoDecoderCandidate(
    val name: String,
    val supportedTypes: List<String>,
    val encoder: Boolean,
    val hardware: Boolean,
    val software: Boolean,
)

internal data class VideoDecoderAttempt(val codecName: String?, val tuned: Boolean)

internal data class VideoDecoderPlan(val attempts: List<VideoDecoderAttempt>, val hardwareOnly: Boolean)

/** Keep hardware start failures from silently loading the CPU with software video. */
internal object VideoDecoderSelection {
    fun plan(mime: String, candidates: List<VideoDecoderCandidate>, preferSoftwareHevc: Boolean): VideoDecoderPlan {
        val decoders = candidates.filter { !it.encoder && mime in it.supportedTypes }.distinctBy { it.name }
        val hardware = decoders.filter { it.hardware && !it.software }
        val explicitSoftware = mime == "video/hevc" && preferSoftwareHevc
        if (hardware.isNotEmpty() && !explicitSoftware) {
            return VideoDecoderPlan(hardware.flatMap {
                listOf(VideoDecoderAttempt(it.name, true), VideoDecoderAttempt(it.name, false))
            }, hardwareOnly = true)
        }
        // Preserve the existing default decoder selection and software fallback on devices
        // without explicit hardware metadata, and the user's software HEVC preference.
        val attempts = listOf(VideoDecoderAttempt(null, true), VideoDecoderAttempt(null, false)) +
            decoders.firstOrNull { it.software }?.let { listOf(VideoDecoderAttempt(it.name, false)) }.orEmpty()
        return VideoDecoderPlan(attempts, hardwareOnly = false)
    }
}
