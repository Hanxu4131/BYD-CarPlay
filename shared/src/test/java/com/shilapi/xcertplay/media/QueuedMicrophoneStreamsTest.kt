package com.shilapi.xcertplay.media

import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class QueuedMicrophoneStreamsTest {
    private class Queue : Executor {
        val tasks = java.util.ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    @Test fun startAndStopReturnBeforeAnyHalWorkAndCancelledInputNeverStarts() {
        val queue = Queue(); val events = mutableListOf<String>()
        val streams = QueuedMicrophoneStreams<String, String>(queue,
            start = { events.add("start:$it"); true }, release = { events.add("stop:$it") })
        streams.start("call") { events.add("create"); "old" }
        assertTrue(events.isEmpty())
        streams.stop("call")
        assertTrue(events.isEmpty())
        queue.drain()
        assertTrue(events.isEmpty())
    }
    @Test fun routeSwitchDuringRecorderStartupCannotCloseTheReplacement() {
        val queue = Queue(); val events = mutableListOf<String>()
        lateinit var streams: QueuedMicrophoneStreams<String, String>
        streams = QueuedMicrophoneStreams(queue, start = { value ->
            events.add("start:$value")
            if (value == "old") {
                streams.stop("call")
                streams.start("call") { "new" }
            }
            true
        }, release = { events.add("stop:$it") })
        streams.start("call") { "old" }; queue.drain()
        assertEquals(listOf("start:old", "stop:old", "start:new"), events)
        streams.stop("call"); queue.drain()
        assertEquals("stop:new", events.last())
    }
    @Test fun closingASinkCancelsQueuedInputsAndReleasesTheActiveInputOnce() {
        val queue = Queue(); val started = mutableListOf<String>(); val released = mutableListOf<String>()
        val streams = QueuedMicrophoneStreams<String, String>(queue,
            start = { started.add(it); true }, release = { released.add(it) })
        streams.start("active") { "active" }; queue.drain()
        streams.start("pending") { "pending" }; streams.close(); streams.close()
        streams.start("late") { "late" }; queue.drain()
        assertEquals(listOf("active"), started); assertEquals(listOf("active"), released)
    }
    @Test fun failedStartupIsReleasedAndCanBeRetried() {
        val queue = Queue(); val released = mutableListOf<String>()
        val streams = QueuedMicrophoneStreams<String, String>(queue,
            start = { it != "failed" }, release = { released.add(it) })
        streams.start("call") { "failed" }; queue.drain()
        streams.start("call") { "working" }; queue.drain(); streams.close(); queue.drain()
        assertEquals(listOf("failed", "working"), released)
    }
}
