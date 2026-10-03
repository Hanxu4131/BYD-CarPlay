package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class SongUpdateDispatcherTest {
    @Test
    fun burstKeepsOnePendingTaskAndOnlyPublishesNewestLine() {
        val tasks = mutableListOf<() -> Unit>()
        val published = mutableListOf<Int?>()
        val updates = SongUpdateDispatcher { tasks += it }
        repeat(1000) { line -> updates.submit { published += line } }
        assertEquals(1, tasks.size)
        tasks.removeAt(0)()
        assertEquals(listOf(999), published)
        updates.submit { published += 1000 }
        updates.submit { published += null } // session ended before the main/OEM task ran
        assertEquals(1, tasks.size)
        tasks.removeAt(0)()
        assertEquals(listOf(999, null), published)
    }

    @Test
    fun updateArrivingDuringOutputRunsAfterCurrentOutput() {
        val tasks = mutableListOf<() -> Unit>()
        val published = mutableListOf<String>()
        val updates = SongUpdateDispatcher { tasks += it }
        updates.submit {
            published += "first"
            updates.submit { published += "obsolete" }
            updates.submit { published += "latest" }
        }
        tasks.removeAt(0)()
        assertEquals(listOf("first"), published)
        assertEquals(1, tasks.size)
        tasks.removeAt(0)()
        assertEquals(listOf("first", "latest"), published)
    }
}
