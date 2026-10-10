package com.shilapi.xcertplay.media

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Coalesce mirror requests within a stream, without dropping another screen's IDR. */
internal class VideoRecoveryDispatch(
    private val executor: Executor,
    private val request: (Int) -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private val pending = mutableSetOf<Int>()

    fun request(type: Int) {
        if (!synchronized(pending) { pending.add(type) }) return
        try {
            executor.execute {
                try { request.invoke(type) }
                catch (error: Exception) { onFailure(error) }
                finally { synchronized(pending) { pending.remove(type) } }
            }
        } catch (_: RejectedExecutionException) {
            synchronized(pending) { pending.remove(type) }
        }
    }
}

/** Resource contention cannot be fixed by repeatedly creating codecs or changing their format. */
internal object VideoCodecFailurePolicy {
    fun resourceUnavailable(errorCode: Int): Boolean =
        errorCode == 1101 || // MediaCodec.CodecException.ERROR_RECLAIMED
            errorCode == 1100 || // MediaCodec.CodecException.ERROR_INSUFFICIENT_RESOURCE
            errorCode == -32 // Qualcomm reports EPIPE after its OMX component is reclaimed.
}

/** Worker-owned cooldown; the existing input/IDR path retries after the deadline. */
internal class VideoCodecRetryGate {
    private var failures = 0
    private var retryAtNs: Long? = null

    val awaitingPicture: Boolean get() = failures > 0

    fun canRetry(nowNs: Long): Boolean = retryAtNs?.let { nowNs >= it } ?: true

    fun failed(nowNs: Long): Long {
        failures = (failures + 1).coerceAtMost(4)
        val delayNs = 500_000_000L shl (failures - 1)
        retryAtNs = nowNs + delayNs
        return delayNs / 1_000_000L
    }

    // Allocation alone does not show that a reclaimed codec is usable again.
    fun pictureDecoded() {
        failures = 0
        retryAtNs = null
    }
}
