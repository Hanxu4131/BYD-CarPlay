package com.shilapi.xcertplay

import android.graphics.Bitmap
import com.shilapi.xcertplay.media.CarPlayNowPlaying
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
    fun pendingArtworkRetainsCurrentUntilDecodedOrExplicitlyCleared() {
        val current = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val decoded = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        assertSame(current, CarPlayMediaKeys.nextArtwork(2, emptyMap(), current))
        assertSame(decoded, CarPlayMediaKeys.nextArtwork(2, mapOf(2 to decoded), current))
        assertNull(CarPlayMediaKeys.nextArtwork(null, emptyMap(), current))
        assertNull(CarPlayMediaKeys.nextArtwork(2, mapOf(2 to null), current))
    }

    @Test
    fun nowPlayingFieldsBecomeAndroidMediaMetadata() {
        val artwork = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val metadata = CarPlayMediaKeys.androidMetadata(
            CarPlayNowPlaying(
                title = "Dreams",
                album = "Rumours",
                artist = "Fleetwood Mac",
                sourceApp = "Music",
                durationMillis = 257_000,
            ),
            artwork,
        )

        assertEquals("Dreams", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertEquals("Dreams", metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
        assertEquals("Fleetwood Mac", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
        assertEquals("Fleetwood Mac", metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
        assertEquals("Rumours", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM))
        assertEquals("Music", metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION))
        assertEquals(257_000, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
        assertEquals(artwork, metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        assertEquals(artwork, metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))
    }


    @Test
    fun publishesPositionFromItsReceiptTimeAndPreservesSeekState() {
        val song = BydSongMetadata.Snapshot("Song", "Artist", BydSongMetadata.Playback.SEEK_FORWARD)
        val state = carPlaySongPlaybackState(song, 12_345L, 6_789L)
        assertEquals(PlaybackState.STATE_FAST_FORWARDING, state.state)
        assertEquals(12_345L, state.position)
        assertEquals(6_789L, state.lastPositionUpdateTime)
        assertEquals(2f, state.playbackSpeed, 0f)
    }

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
