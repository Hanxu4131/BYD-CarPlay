package com.shilapi.xcertplay.media

/** Local frame timing only. A new codec or output surface invalidates its old callbacks. */
internal class VideoFrameTiming(private val maxTrackedFrames: Int = 256) {
    data class Ages(val codecNs: Long, val receivedNs: Long)
    private data class Frame(val receivedNs: Long, val submittedNs: Long)
    private val frames = LinkedHashMap<Long, Frame>()
    private var generation = 0L

    init { require(maxTrackedFrames > 0) }

    @Synchronized fun reset(): Long {
        frames.clear()
        return ++generation
    }

    @Synchronized fun isCurrent(epoch: Long): Boolean = epoch == generation

    @Synchronized fun submitted(ptsUs: Long, receivedNs: Long, submittedNs: Long): Long? {
        if (submittedNs < receivedNs) return null
        frames[ptsUs] = Frame(receivedNs, submittedNs)
        while (frames.size > maxTrackedFrames) frames.remove(frames.keys.first())
        return submittedNs - receivedNs
    }

    @Synchronized fun released(ptsUs: Long, releasedNs: Long): Ages? =
        frames[ptsUs]?.let { ages(it, releasedNs) }

    @Synchronized fun presented(epoch: Long, ptsUs: Long, renderedNs: Long): Ages? {
        if (epoch != generation) return null
        val frame = frames[ptsUs] ?: return null
        val result = ages(frame, renderedNs) ?: return null
        frames.remove(ptsUs)
        return result
    }

    private fun ages(frame: Frame, endNs: Long): Ages? =
        if (endNs < frame.submittedNs) null
        else Ages(endNs - frame.submittedNs, endNs - frame.receivedNs)
}
