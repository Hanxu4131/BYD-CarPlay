package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray, val codedWidth: Int? = null, val codedHeight: Int? = null) : VideoJob {
        fun sameDecoderConfig(other: Config, fallbackWidth: Int, fallbackHeight: Int): Boolean =
            codec == other.codec && codecData.contentEquals(other.codecData) &&
                (codedWidth ?: fallbackWidth) == (other.codedWidth ?: fallbackWidth) &&
                (codedHeight ?: fallbackHeight) == (other.codedHeight ?: fallbackHeight)
    }
    data class Frame(val nalus: ByteArray, val receivedNs: Long = System.nanoTime()) : VideoJob
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    data object Resync : VideoJob
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec)
    fun onQueued() { needsKeyFrame = false }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Bursts may be old without a growing backlog; recovery also checks age and queue progress.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    private val jobs = LinkedBlockingQueue<VideoJob>()
    private var peakFrames = 0
    private var peakBytes = 0L
    private var overflows = 0
    private var staleRecoveries = 0

    @Synchronized fun recordStaleRecovery() { staleRecoveries++ }

    data class Backlog(val pendingFrames: Int, val newestPendingReceivedNs: Long?)

    /** Called by the sole consumer after taking its current frame. Control jobs end this chain. */
    @Synchronized fun backlogAfterCurrent(): Backlog {
        var frames = 0
        var newest: Long? = null
        for (job in jobs) {
            if (job !is VideoJob.Frame) break
            frames++
            newest = newest?.let { maxOf(it, job.receivedNs) } ?: job.receivedNs
        }
        return Backlog(frames, newest)
    }

    /** Read only every stats window; do not infer on-screen FPS from queue depth. */
    @Synchronized fun takeDiagnostics(): String {
        val frames = jobs.filterIsInstance<VideoJob.Frame>()
        val currentBytes = frames.sumOf { it.nalus.size.toLong() }
        val result = "pendingFrames=${frames.size} pendingBytes=$currentBytes " +
            "peakPendingFrames=$peakFrames peakPendingBytes=$peakBytes " +
            "queueOverflows=$overflows staleRecoveries=$staleRecoveries"
        peakFrames = frames.size; peakBytes = currentBytes; overflows = 0; staleRecoveries = 0
        return result
    }

    @Synchronized fun offer(job: VideoJob) {
        if (job is VideoJob.Frame) {
            val frames = jobs.filterIsInstance<VideoJob.Frame>()
            val bytes = frames.sumOf { it.nalus.size.toLong() }
            val overflow = frames.size >= maxFrames || bytes + job.nalus.size > maxBytes
            if (overflow) {
                overflows++
                discardFrames()
                jobs.offer(VideoJob.Resync)
            }
            // A single oversized frame is also a lost reference chain.
            if (job.nalus.size > maxBytes) return
            peakFrames = maxOf(peakFrames, (if (overflow) 0 else frames.size) + 1)
            peakBytes = maxOf(peakBytes, (if (overflow) 0L else bytes) + job.nalus.size)
        }
        jobs.offer(job)
    }

    /** Drop only the invalidated chain; the next config/surface owns later frames. */
    @Synchronized fun discardCurrentChain() {
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            when (iterator.next()) {
                is VideoJob.Config, is VideoJob.SurfaceChanged -> return
                is VideoJob.Frame, VideoJob.Resync -> iterator.remove()
            }
        }
    }

    @Synchronized fun discardFrames() {
        jobs.removeIf { it is VideoJob.Frame || it is VideoJob.Resync }
    }

    fun poll(timeoutMillis: Long): VideoJob? = jobs.poll(timeoutMillis, TimeUnit.MILLISECONDS)
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. */
internal object VideoInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}
