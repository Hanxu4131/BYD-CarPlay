package com.shilapi.xcertplay.media

/** Measures PCM amplitude without retaining speech or mistaking short pauses for failure. */
internal class MicrophoneSignalHealth(private val samplesPerSecond: Int) {
    private var samples = 0L
    private var squared = 0L
    private var peak = 0
    private var consecutiveZeroSamples = 0L
    private var fallbackUsed = false

    init { require(samplesPerSecond > 0) }

    fun observe(bytes: ByteArray, count: Int): Boolean {
        require(count in 0..bytes.size)
        for (offset in 0 until count - 1 step 2) {
            val value = ((bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()
            samples++
            squared += value.toLong() * value
            peak = maxOf(peak, kotlin.math.abs(value))
            consecutiveZeroSamples = if (value == 0) consecutiveZeroSamples + 1 else 0
        }
        if (!fallbackUsed && consecutiveZeroSamples >= samplesPerSecond.toLong() * 2) {
            fallbackUsed = true
            return true
        }
        return false
    }

    fun summary(): String {
        val rms = if (samples == 0L) 0 else kotlin.math.sqrt(squared.toDouble() / samples).toInt()
        val report = "samples=$samples peak=$peak rms=$rms zeroMs=${consecutiveZeroSamples * 1000 / samplesPerSecond}"
        samples = 0; squared = 0; peak = 0
        return report
    }
}
