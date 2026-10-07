package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test

class NowPlayingMetadataPublicationTest {
    @Test fun positionAndPlaybackChangesDoNotResendMetadataOrArtwork() {
        val publication = NowPlayingMetadataPublication<Any>()
        val owner = Any()
        val artwork = Any()
        val info = CarPlayNowPlaying(title = "歌曲", artist = "歌手", elapsedMillis = 1000, playing = true)
        var calls = 0
        assertTrue(publication.publish(owner, info, artwork) { calls++ })
        assertFalse(publication.publish(owner, info.copy(elapsedMillis = 1500), artwork) { calls++ })
        assertFalse(publication.publish(owner, info.copy(playing = false), artwork) { calls++ })
        assertEquals(1, calls)
    }

    @Test fun lyricTitlesAndEveryDisplayedFieldPublishImmediately() {
        val initial = CarPlayNowPlaying(title = "第一句", artist = "歌手", album = "专辑",
            durationMillis = 10_000, sourceApp = "Music")
        val changes = listOf(
            initial.copy(title = "下一句"), initial.copy(artist = null), initial.copy(album = null),
            initial.copy(durationMillis = 11_000), initial.copy(sourceApp = "另一应用"),
        )
        for (changed in changes) {
            val publication = NowPlayingMetadataPublication<Any>()
            val owner = Any()
            var displayed = initial
            publication.publish(owner, initial, null) {}
            assertTrue(publication.publish(owner, changed, null) { displayed = changed })
            assertEquals(changed, displayed)
        }
    }

    @Test fun pendingArtworkIdDoesNotResendTheRetainedImageButDecodedArtDoes() {
        val publication = NowPlayingMetadataPublication<Any>()
        val owner = Any()
        val firstArt = Any()
        val info = CarPlayNowPlaying(title = "歌曲", artworkTransferId = 1)
        publication.publish(owner, info, firstArt) {}
        assertFalse(publication.publish(owner, info.copy(artworkTransferId = 2), firstArt) { fail() })
        assertTrue(publication.publish(owner, info.copy(artworkTransferId = 2), Any()) {})
        assertTrue(publication.publish(owner, info.copy(artworkTransferId = null), null) {})
        assertFalse(publication.publish(owner, info.copy(artworkTransferId = null), null) { fail() })
    }

    @Test fun identicalSnapshotsStillPublishToANewSessionAndAfterReset() {
        val publication = NowPlayingMetadataPublication<Any>()
        val owner = Any()
        val info = CarPlayNowPlaying(title = "歌曲")
        var calls = 0
        publication.publish(owner, info, null) { calls++ }
        assertFalse(publication.publish(owner, info, null) { calls++ })
        assertTrue(publication.publish(Any(), info, null) { calls++ })
        publication.reset()
        assertTrue(publication.publish(owner, info, null) { calls++ })
        assertEquals(3, calls)
    }

    @Test fun failedPublicationDoesNotSuppressItsRetry() {
        val publication = NowPlayingMetadataPublication<Any>()
        val owner = Any()
        val info = CarPlayNowPlaying(title = "歌曲")
        val failure = IllegalStateException("session rejected update")
        try {
            publication.publish(owner, info, null) { throw failure }
            fail("Failure must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertTrue(publication.publish(owner, info, null) {})
    }
}
