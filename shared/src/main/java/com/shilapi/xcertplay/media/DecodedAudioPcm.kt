package com.shilapi.xcertplay.media

import java.nio.ByteBuffer

/** Return app-owned PCM only after releasing the codec slot, including when copying fails. */
internal object DecodedAudioPcm {
    inline fun copyAndRelease(source: () -> ByteBuffer?, offset: Int, length: Int,
        scratch: ByteArray, release: () -> Unit): ByteArray? = try {
        if (length <= 0) null else source()?.let { output ->
            val owned = if (length > scratch.size) ByteArray(length) else scratch
            output.limit(offset + length)
            output.position(offset)
            output.get(owned, 0, length)
            owned
        }
    } finally {
        release()
    }
}
