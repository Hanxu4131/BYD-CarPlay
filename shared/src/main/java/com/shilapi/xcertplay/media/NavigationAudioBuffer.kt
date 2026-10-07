package com.shilapi.xcertplay.media

/** Navigation had 80 ms queued while the loaded head unit stalled writes for 180 ms. */
internal object NavigationAudioBuffer {
    const val CAPACITY_MILLIS = 600
    const val START_MILLIS = 300

    fun plan(sampleRate: Int, channels: Int, minBufferBytes: Int): MediaAudioBuffer.Plan {
        val lowLatency = MediaAudioBuffer.plan(false, sampleRate, channels, minBufferBytes,
            MediaAudioBuffer.DEFAULT_MILLIS)
        val bytesPerSecond = sampleRate.toLong() * channels.coerceIn(1, 2) * 2
        return MediaAudioBuffer.Plan(
            trackBufferBytes = maxOf(lowLatency.trackBufferBytes,
                (bytesPerSecond * CAPACITY_MILLIS / 1000).toInt()),
            startBytes = maxOf(lowLatency.startBytes,
                (bytesPerSecond * START_MILLIS / 1000).toInt()),
        )
    }
}
