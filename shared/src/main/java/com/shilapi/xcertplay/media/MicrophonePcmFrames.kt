package com.shilapi.xcertplay.media

/** Preserves short reads and emits only complete PCM frames, without adding silence. */
internal class MicrophonePcmFrames(val readSize: Int) {
    init { require(readSize > 0) }
    private val frame = ByteArray(readSize)
    private var filled = 0

    /** The callback must consume the reused frame before returning. */
    fun append(bytes: ByteArray, count: Int, emit: (ByteArray) -> Unit) {
        require(count in 0..bytes.size)
        var offset = 0
        while (offset < count) {
            val copied = minOf(frame.size - filled, count - offset)
            bytes.copyInto(frame, filled, offset, offset + copied)
            filled += copied
            offset += copied
            if (filled == frame.size) {
                emit(frame)
                filled = 0
            }
        }
    }
}
