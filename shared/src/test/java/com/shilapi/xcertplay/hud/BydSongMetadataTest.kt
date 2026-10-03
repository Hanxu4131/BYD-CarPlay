package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BydSongMetadataTest {
    @Test
    fun queuedConsumersReadTheLatestSongAndCannotRestoreADisconnectedSong() {
        BydSongMetadata.publish(null)
        val queue = mutableListOf<() -> Unit>()
        val received = mutableListOf<BydSongMetadata.Snapshot?>()
        BydSongMetadata.listener = { queue += { received += BydSongMetadata.snapshot() } }
        try {
            BydSongMetadata.publish(BydSongMetadata.Snapshot("旧歌", "旧歌手", BydSongMetadata.Playback.PLAYING))
            BydSongMetadata.publish(BydSongMetadata.Snapshot("新歌", null, BydSongMetadata.Playback.PAUSED))
            queue.forEach { it() }
            assertTrue(received.all { it?.title == "新歌" })
            received.clear()
            BydSongMetadata.publish(null)
            queue.forEach { it() }
            assertTrue(received.all { it == null })
        } finally {
            BydSongMetadata.listener = null
            BydSongMetadata.publish(null)
        }
    }

    @Test
    fun oemSwitchAndArtistAreIndependentAndOldSyncDisableDoesNotSuppressMetadata() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("diplay_byd_outputs", 0)
        prefs.edit().clear().putBoolean("cluster_song", false).commit()
        assertFalse(BydOutputSettings.clusterSongLegacy(context))
        assertTrue(BydOutputSettings.clusterSongArtist(context))
        BydOutputSettings.setClusterSongLegacy(context, true)
        BydOutputSettings.setClusterSongArtist(context, false)
        assertTrue(prefs.getBoolean("cluster_song_legacy_instrument", false))
        assertFalse(BydOutputSettings.clusterSongArtist(context))
        BydOutputSettings.setClusterSongLegacy(context, false)
        BydClusterSong.attach(context)
        try {
            BydClusterSong.onFrame(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5001) {
                group(0) { string(1, "歌词"); string(12, "歌手") }
            })
            assertEquals(BydSongMetadata.Snapshot("歌词", "歌手", BydSongMetadata.Playback.STOPPED),
                BydSongMetadata.snapshot())
            BydOutputSettings.setClusterSongLegacy(context, false)
            BydClusterSong.settingChanged()
            assertEquals("歌手", BydSongMetadata.snapshot()?.artist)
            BydClusterSong.end()
            assertNull(BydSongMetadata.snapshot())
        } finally {
            BydOutputSettings.setClusterSongLegacy(context, false)
            BydClusterSong.end()
            prefs.edit().clear().commit()
        }
    }
}
