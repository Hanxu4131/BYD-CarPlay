package com.shilapi.xcertplay.media

import java.io.Closeable
import java.util.concurrent.Executor

/** Recorder/HAL work must not block RTP receive or the control connection's reply. */
internal class QueuedMicrophoneStreams<K, T>(
    private val executor: Executor,
    private val start: (T) -> Boolean,
    private val release: (T) -> Unit,
    private val report: (Exception) -> Unit = {},
) : Closeable {
    private class Entry<T>(val create: () -> T) { var resource: T? = null }
    private val lock = Any()
    private val entries = HashMap<K, Entry<T>>()
    private var closed = false

    fun start(key: K, create: () -> T) {
        synchronized(lock) {
            if (closed || key in entries) return
            val entry = Entry(create)
            entries[key] = entry
            executor.execute {
                try {
                    if (!current(key, entry)) return@execute
                    entry.resource = entry.create()
                    if (!current(key, entry) || !start(entry.resource!!)) {
                        synchronized(lock) { if (entries[key] === entry) entries.remove(key) }
                        dispose(entry)
                    } else if (!current(key, entry)) dispose(entry)
                } catch (error: Exception) {
                    synchronized(lock) { if (entries[key] === entry) entries.remove(key) }
                    dispose(entry)
                    report(error)
                }
            }
        }
    }

    fun stop(key: K) {
        synchronized(lock) {
            val entry = entries.remove(key) ?: return
            executor.execute { dispose(entry) }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            val pending = entries.values.toList()
            entries.clear()
            executor.execute { pending.forEach(::dispose) }
        }
    }

    private fun current(key: K, entry: Entry<T>): Boolean =
        synchronized(lock) { !closed && entries[key] === entry }

    // Only the single recorder executor reads or releases resources.
    private fun dispose(entry: Entry<T>) {
        val resource = entry.resource ?: return
        entry.resource = null
        try { release(resource) } catch (error: Exception) { report(error) }
    }
}
