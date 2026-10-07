package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** Cache only successful publications; a replacement media session must receive its own metadata. */
internal class NowPlayingMetadataPublication<Image : Any> {
    private var lastOwner: Any? = null
    private var lastInfo: CarPlayNowPlaying? = null
    private var lastArtwork: Image? = null

    fun publish(owner: Any, info: CarPlayNowPlaying, artwork: Image?, update: () -> Unit): Boolean {
        val previous = lastInfo
        if (lastOwner === owner && previous != null && !metadataChanged(previous, info) &&
            lastArtwork === artwork) return false
        update()
        lastOwner = owner
        lastInfo = info
        lastArtwork = artwork
        return true
    }

    fun reset() {
        lastOwner = null
        lastInfo = null
        lastArtwork = null
    }

    // Only fields written by androidMetadata; position and transport IDs have separate owners.
    private fun metadataChanged(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        previous.title != next.title || previous.artist != next.artist || previous.album != next.album ||
            previous.durationMillis != next.durationMillis || previous.sourceApp != next.sourceApp
}
