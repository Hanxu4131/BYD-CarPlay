package com.shilapi.xcertplay

import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.shilapi.xcertplay.hud.BydSongMetadata
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlaySongMetadataTest {
    @Test
    fun forwardsThePhoneTitleAndArtistWithoutInventingLyrics() {
        val title = "手机应用的歌词行".repeat(50)
        val metadata = carPlayMediaMetadata(BydSongMetadata.Snapshot(title, "歌手", BydSongMetadata.Playback.PLAYING))
        assertEquals(title, metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertEquals(title, metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
        assertEquals("歌手", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
        assertEquals("歌手", metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
        val next = carPlayMediaMetadata(BydSongMetadata.Snapshot("下一首", null, BydSongMetadata.Playback.PAUSED))
        assertNull(next.getString(MediaMetadata.METADATA_KEY_ARTIST))
    }

    @Test
    fun exposesPauseStopAndDisconnectWithZeroPlaybackSpeed() {
        for ((phone, android) in listOf(
            BydSongMetadata.Playback.PLAYING to PlaybackState.STATE_PLAYING,
            BydSongMetadata.Playback.PAUSED to PlaybackState.STATE_PAUSED,
            BydSongMetadata.Playback.STOPPED to PlaybackState.STATE_STOPPED,
        )) {
            val state = carPlaySongPlaybackState(BydSongMetadata.Snapshot("歌曲", null, phone))
            assertEquals(android, state.state)
            assertEquals(if (phone == BydSongMetadata.Playback.PLAYING) 1f else 0f, state.playbackSpeed, 0f)
            assertEquals(PlaybackState.PLAYBACK_POSITION_UNKNOWN, state.position)
        }
        assertEquals(PlaybackState.STATE_STOPPED, carPlaySongPlaybackState(null).state)
        assertEquals(0f, carPlaySongPlaybackState(null).playbackSpeed, 0f)
    }
}
