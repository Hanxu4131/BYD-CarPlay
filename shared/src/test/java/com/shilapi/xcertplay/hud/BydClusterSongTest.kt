package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BydClusterSongTest {
    private fun update(block: com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder.() -> Unit) =
        Iap2Messages.buildRaw(ClusterSongState.NOW_PLAYING_UPDATE, block)

    @Test
    fun followsTitleArtistAndPlaybackStatus() {
        val state = ClusterSongState()

        assertEquals(ClusterSong("Numb — Linkin Park", false),
            state.accept(update { group(0) { string(1, "Numb"); string(12, "Linkin Park") } }))
        assertEquals(ClusterSong("Numb — Linkin Park", true), state.accept(update { group(1) { u8(0, 1) } }))
        // Elapsed time alone changes nothing on the card.
        assertNull(state.accept(update { group(1) { u32(1, 120_706L) } }))
        assertEquals(ClusterSong("Numb — Linkin Park", false), state.accept(update { group(1) { u8(0, 2) } }))
        // The phone sends changed fields only; title-only lyrics keep the artist.
        assertEquals(ClusterSong("Podcast — Linkin Park", false), state.accept(update { group(0) { string(1, "Podcast") } }))
        assertEquals(ClusterSong("Podcast — Host", false), state.accept(update { group(0) { string(12, "Host") } }))
    }

    @Test
    fun nothingWithoutATitleOrForOtherMessages() {
        val state = ClusterSongState()
        assertNull(state.accept(update { group(1) { u8(0, 1) } }))
        assertNull(state.accept(Iap2Messages.buildRaw(0x5201) { group(0) { string(1, "Numb") } }))
        assertNull(state.current())

        state.accept(update { group(0) { string(1, "Numb") } })
        state.clear()
        assertNull(state.current())
    }

    @Test
    fun clearedTitlesForgetThePreviousSongUntilANewTitleArrives() {
        val state = ClusterSongState()
        state.accept(update { group(0) { string(1, "Previous song"); string(12, "Artist") } })
        state.accept(update { group(0) { string(1, "") } })
        assertNull(state.current())
        state.accept(update { group(1) { u8(0, 1) } })
        assertNull(state.current())
        assertEquals(ClusterSong("Next song", true),
            state.accept(update { group(0) { string(1, "Next song") } }))
        state.accept(update { group(0) { string(1, "  ") } })
        assertNull(state.current())
    }

    @Test
    fun androidMetadataKeepsFullFieldsAndDistinguishesStoppedFromPaused() {
        val state = ClusterSongState()
        val title = "手机实际歌词行".repeat(50)
        state.accept(update { group(0) { string(1, title); string(12, "歌手") }; group(1) { u8(0, 1) } })
        assertEquals(BydSongMetadata.Snapshot(title, "歌手", BydSongMetadata.Playback.PLAYING), state.mediaCurrent())
        assertTrue(state.current()!!.text.length < title.length)
        state.accept(update { group(1) { u8(0, 2) } })
        assertEquals(BydSongMetadata.Playback.PAUSED, state.mediaCurrent()!!.playback)
        state.accept(update { group(1) { u8(0, 0) } })
        assertEquals(BydSongMetadata.Playback.STOPPED, state.mediaCurrent()!!.playback)
        state.accept(update { group(0) { string(1, "下一首") } })
        assertEquals("歌手", state.mediaCurrent()!!.artist)
        state.accept(update { group(0) { string(12, "") } })
        assertNull(state.mediaCurrent()!!.artist)
        state.clear()
        assertNull(state.mediaCurrent())
    }

    @Test
    fun lyricUpdatesMergeFieldsWithoutLosingTheArtist() {
        val state = ClusterSongState()
        state.accept(update { group(0) { string(1, "歌曲名"); string(12, "歌手") } })
        state.accept(update { group(0) { string(1, "熟悉的安全感") } })
        assertEquals(BydSongMetadata.Snapshot("熟悉的安全感", "歌手", BydSongMetadata.Playback.STOPPED), state.mediaCurrent())
        state.accept(update { group(0) { string(1, "分享热汤") } })
        assertEquals("歌手", state.mediaCurrent()!!.artist)
        state.accept(update { group(0) { string(12, "另一位歌手") } })
        assertEquals("分享热汤", state.mediaCurrent()!!.title)
        assertEquals("另一位歌手", state.mediaCurrent()!!.artist)
        state.accept(update { group(0) { string(1, "") } })
        state.accept(update { group(0) { string(1, "新歌曲") } })
        assertNull(state.mediaCurrent()!!.artist)
    }

    @Test
    fun textFitsTheDashboard() {
        assertNull(ClusterSongState.text("  ", "Artist"))
        assertEquals("Title", ClusterSongState.text(" Title ", ""))

        val long = ClusterSongState.text("Пісня".repeat(40), "Виконавець")!!
        assertTrue(long.toByteArray(Charsets.UTF_16LE).size <= ClusterSongState.MAX_TEXT_BYTES)
        assertEquals(127, long.length)

        // An emoji is never cut in half.
        val emoji = ClusterSongState.text("a" + "🎵".repeat(100), null)!!
        assertTrue(emoji.toByteArray(Charsets.UTF_16LE).size <= ClusterSongState.MAX_TEXT_BYTES)
        assertTrue(!Character.isHighSurrogate(emoji.last()))
    }
    @Test
    fun hidingArtistChangesOnlyTheOemCardAndKeepsTitleOnlyLyricsMerged() {
        val state = ClusterSongState()
        state.accept(update { group(0) { string(1, "歌词行"); string(12, "歌手") } })
        assertEquals("歌词行 — 歌手", state.current(true)?.text)
        assertEquals("歌词行", state.current(false)?.text)
        assertEquals("歌手", state.mediaCurrent()?.artist)
        state.accept(update { group(0) { string(1, "下一句") } })
        assertEquals("下一句", state.current(false)?.text)
        assertEquals("下一句 — 歌手", state.current(true)?.text)
        assertEquals("歌手", state.mediaCurrent()?.artist)
    }

    @Test
    fun consecutiveLyricsWriteOnlyTextAndPlaybackChangesWriteOnlyChangedControls() {
        val first = ClusterSongWriteCommand.forSong(null, 1, "first")
        assertEquals("11 1 first", first.args)
        assertEquals(setOf("source", "state", "text"), first.fields)
        val lyric = ClusterSongWriteCommand.forSong(1, 1, "lyric")
        assertEquals("- - lyric", lyric.args)
        assertEquals(setOf("text"), lyric.fields)
        val paused = ClusterSongWriteCommand.forSong(1, 2, "lyric")
        assertEquals("- 2 lyric", paused.args)
        assertEquals(setOf("state", "text"), paused.fields)
        assertTrue(ClusterSongWriteCommand.succeeded("text=0\ntiming textMs:12", lyric.fields))
        assertTrue(!ClusterSongWriteCommand.succeeded("", lyric.fields))
        assertTrue(!ClusterSongWriteCommand.succeeded("text=ERR failure", lyric.fields))
        assertTrue(!ClusterSongWriteCommand.succeeded("source=0\ntext=0", first.fields))
    }

    @Test
    fun sourceFailureDoesNotInvalidateAcknowledgedStateOrRetrySourceForEachLyric() {
        val cache = ClusterSongControlCache()
        val first = cache.command(1, "first", 0L)
        cache.accept("source=1\nstate=0\ntext=0", first, 1, 1L)
        assertEquals(1, cache.playback)
        assertTrue(!ClusterSongWriteCommand.succeeded("source=1\nstate=0\ntext=0", first.fields))
        assertEquals("- - next", cache.command(1, "next", 2L).args)
        assertTrue(!cache.needsRefresh(1, 2L))
        val retry = cache.command(1, "latest", java.util.concurrent.TimeUnit.SECONDS.toNanos(31))
        assertEquals("11 - latest", retry.args)
        cache.accept("source=0\ntext=0", retry, 1, java.util.concurrent.TimeUnit.SECONDS.toNanos(31))
        assertTrue(!cache.needsRefresh(1, java.util.concurrent.TimeUnit.SECONDS.toNanos(32)))
        cache.reset()
        assertEquals("11 1 fresh", cache.command(1, "fresh", 0L).args)
    }

    @Test
    fun workerProtocolRejectsMissingAuthenticationUnknownControlsAndOversizedText() {
        val token = "0123456789abcdef0123456789abcdef"
        val encoded = java.util.Base64.getEncoder().encodeToString("歌词".toByteArray())
        assertEquals("write", ClusterSongWorkerProtocol.parse("$token write 11 1 $encoded", token)?.operation)
        assertEquals("stop", ClusterSongWorkerProtocol.parse("$token stop", token)?.operation)
        assertNull(ClusterSongWorkerProtocol.parse("wrong write 11 1 $encoded", token))
        assertNull(ClusterSongWorkerProtocol.parse("$token write 12 1 $encoded", token))
        assertNull(ClusterSongWorkerProtocol.parse("$token write 11 4 $encoded", token))
        assertNull(ClusterSongWorkerProtocol.parse("$token execute settings", token))
        assertNull(ClusterSongWorkerProtocol.parse("$token write 11 1 !!!", token))
        val long = java.util.Base64.getEncoder().encodeToString("字".repeat(128).toByteArray())
        assertNull(ClusterSongWorkerProtocol.parse("$token write 11 1 $long", token))
        assertNull(ClusterSongWorkerProtocol.readLine(java.io.ByteArrayInputStream("x".repeat(1025).toByteArray())))
        assertEquals("ping", ClusterSongWorkerProtocol.readLine(java.io.ByteArrayInputStream("ping\n".toByteArray())))
    }

    @Test
    fun onlyAnActiveEnabledSessionCanWarmAndEndInvalidatesPendingInitialization() {
        val lease = ClusterSongLeaseState()
        assertNull(lease.ticket(true)) // App-open initialization is not a session start.
        lease.start()
        assertNull(lease.ticket(false)) // The original OEM preference is off.
        val starting = lease.ticket(true)!!
        assertTrue(lease.valid(starting, true)) // No title is required to prewarm.
        assertTrue(!lease.valid(starting, false)) // Off is respected before initialization returns.
        lease.end()
        assertTrue(!lease.valid(starting, true))
        assertNull(lease.ticket(true)) // Periodic renewal cannot revive a disconnected session.
        lease.start()
        val next = lease.ticket(true)!!
        assertTrue(!lease.valid(starting, true))
        lease.settingChanged()
        assertTrue(!lease.valid(next, true)) // Even off followed by on invalidates the older startup.
        assertTrue(lease.ticket(true) != null)
    }

    @Test
    fun anUntouchedPrewarmedWorkerCanCloseWithoutAnOemSetter() {
        val token = "0123456789abcdef0123456789abcdef"
        val request = ClusterSongWorkerProtocol.parse("$token close", token)!!
        assertEquals("close", request.operation)
        assertTrue(request.args.isEmpty())
        assertNull(ClusterSongWorkerProtocol.parse("wrong close", token))
    }

}
