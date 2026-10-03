package com.shilapi.xcertplay.media

import android.util.Log

/** Five-second video counters that separate network/iPhone gaps from decoder throughput. */
internal class VideoStats(
    private val label: String = "",
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var windowStartNs = nanoTime()
    private var lastArrivalNs = 0L
    private var received = 0
    private var rendered = 0
    private var recoveries = 0
    private var bytes = 0L
    private var maxArrivalGapNs = 0L
    private var touchSamples = 0
    private var touchLatencySumNs = 0L
    private var maxTouchLatencyNs = 0L
    private var renderTouchSamples = 0
    private var renderTouchSumNs = 0L
    private var maxRenderTouchNs = 0L
    private val queueAge = AgeSamples()
    private val codecOutputAge = AgeSamples()
    private val receiveToRelease = AgeSamples()
    private val codecPresentationAge = AgeSamples()
    private val receiveToPresent = AgeSamples()

    @Synchronized fun onSubmitted(queueNs: Long) { queueAge.add(queueNs) }

    @Synchronized fun onReleasedAge(ages: VideoFrameTiming.Ages) {
        codecOutputAge.add(ages.codecNs)
        receiveToRelease.add(ages.receivedNs)
    }

    @Synchronized fun onPresented(ages: VideoFrameTiming.Ages) {
        codecPresentationAge.add(ages.codecNs)
        receiveToPresent.add(ages.receivedNs)
    }

    /** Keep historical shown/touch2render fields, but explicitly identify their release stage. */
    @Synchronized internal fun timingSummary(seconds: Double): String =
        "shownStage=released presented=%.1ffps ".format(receiveToPresent.count / seconds) +
            queueAge.summary("queueAge") + " " + codecOutputAge.summary("codecOutputAge") + " " +
            receiveToRelease.summary("recvToRelease") + " " +
            codecPresentationAge.summary("codecPresentAge") + " " + receiveToPresent.summary("recvToPresent")

    @Synchronized fun onReceived(size: Int) {
        val now = nanoTime()
        val gap = now - lastArrivalNs
        if (lastArrivalNs != 0L && gap < IDLE_GAP_NS) maxArrivalGapNs = maxOf(maxArrivalGapNs, gap)
        lastArrivalNs = now
        // Touches only change the main screen; a second stream must not consume their samples.
        val touchLatency = if (label.isEmpty()) TouchLatencyProbe.onFrame(now) else -1L
        if (touchLatency >= 0) {
            touchSamples++
            touchLatencySumNs += touchLatency
            maxTouchLatencyNs = maxOf(maxTouchLatencyNs, touchLatency)
        }
        received++
        bytes += size
    }

    @Synchronized fun onRendered() {
        rendered++
        val latency = if (label.isEmpty()) TouchLatencyProbe.onRendered(nanoTime()) else -1L
        if (latency >= 0) {
            renderTouchSamples++
            renderTouchSumNs += latency
            maxRenderTouchNs = maxOf(maxRenderTouchNs, latency)
        }
    }

    @Synchronized fun onRecovery() { recoveries++ }

    @Synchronized fun logIfDue(): String? {
        val now = nanoTime()
        val elapsedNs = now - windowStartNs
        if (elapsedNs < WINDOW_NS) return null
        val seconds = elapsedNs / 1e9
        if (received == 0 && rendered == 0 && touchSamples == 0 && renderTouchSamples == 0 &&
            receiveToPresent.count == 0 && queueAge.count == 0) { windowStartNs = now; return null }
        val touchAvgMs = if (touchSamples == 0) -1 else touchLatencySumNs / touchSamples / 1_000_000
        val line = ("video stats$label rx=%.1ffps shown=%.1ffps maxGap=%dms kbps=%d recoveries=%d " +
            "touch2frame avg=%dms max=%dms n=%d touch2render avg=%dms max=%dms n=%d touchSendMax=%dms").format(
            received / seconds, rendered / seconds, maxArrivalGapNs / 1_000_000,
            (bytes * 8 / 1000 / seconds).toLong(), recoveries,
            touchAvgMs, maxTouchLatencyNs / 1_000_000, touchSamples,
            if (renderTouchSamples == 0) -1 else renderTouchSumNs / renderTouchSamples / 1_000_000,
            maxRenderTouchNs / 1_000_000, renderTouchSamples,
            if (label.isEmpty()) TouchLatencyProbe.takeMaxSendNs() / 1_000_000 else 0L,
        ) + " " + timingSummary(seconds)
        Log.i(TAG, line)
        windowStartNs = now
        received = 0; rendered = 0; recoveries = 0; bytes = 0; maxArrivalGapNs = 0
        touchSamples = 0; touchLatencySumNs = 0; maxTouchLatencyNs = 0
        renderTouchSamples = 0; renderTouchSumNs = 0; maxRenderTouchNs = 0
        listOf(queueAge, codecOutputAge, receiveToRelease, codecPresentationAge, receiveToPresent)
            .forEach(AgeSamples::clear)
        return line
    }

    private class AgeSamples {
        var count = 0
            private set
        private var totalNs = 0L
        private var maxNs = 0L

        fun add(ageNs: Long) {
            if (ageNs < 0) return
            count++
            totalNs += ageNs
            maxNs = maxOf(maxNs, ageNs)
        }

        fun summary(name: String): String = "$name avg=" +
            (if (count == 0) -1 else totalNs / count / 1_000_000) + "ms max=" +
            (if (count == 0) -1 else maxNs / 1_000_000) + "ms n=$count"

        fun clear() { count = 0; totalNs = 0; maxNs = 0 }
    }

    private companion object {
        const val TAG = "DiPlay-VideoStats"
        const val WINDOW_NS = 5_000_000_000L
        const val IDLE_GAP_NS = 2_000_000_000L // longer gaps are a static screen, not lag
    }
}
