package com.shilapi.xcertplay.media

import java.io.Closeable

/** Restores the mode captured before the first call, after the last call has stopped. */
internal class CommunicationAudioMode<K>(
    private val communicationMode: Int,
    private val readMode: () -> Int,
    private val writeMode: (Int) -> Unit,
    private val onFailure: (RuntimeException) -> Unit = {},
) : Closeable {
    private val owners = HashSet<K>()
    private var savedMode: Int? = null

    @Synchronized
    fun acquire(owner: K) {
        if (owner in owners) return
        try {
            if (savedMode == null) {
                val previous = readMode()
                writeMode(communicationMode)
                savedMode = previous
            }
            owners.add(owner)
        } catch (error: RuntimeException) {
            onFailure(error)
        }
    }

    @Synchronized
    fun release(owner: K) {
        owners.remove(owner)
        if (owners.isEmpty()) restore()
    }

    @Synchronized
    override fun close() {
        owners.clear()
        restore()
    }

    private fun restore() {
        val previous = savedMode ?: return
        try {
            // Another audio client may have changed mode while CarPlay was active.
            if (readMode() == communicationMode) writeMode(previous)
            savedMode = null
        } catch (error: RuntimeException) {
            // Keep the saved mode so a later close can retry a failed restoration.
            onFailure(error)
        }
    }
}
