package io.ymusic.ui

import io.ymusic.dynamicengine.yt.YtFeedItem
import kotlin.test.Test
import kotlin.test.assertEquals

class RankMusicFirstTest {

    private fun video(id: String, music: Boolean = false, badge: String? = null) =
        YtFeedItem(kind = YtFeedItem.KIND_VIDEO, id = id, title = id, musicVideo = music, ownerBadge = badge)

    @Test
    fun mix_first_then_music_bands_with_playlists_and_channels_pinned() {
        val items = listOf(
            video("plain1"),
            video("verified1", badge = YtFeedItem.OWNER_BADGE_VERIFIED),
            // A regular playlist is pinned: it keeps slot 2 exactly.
            YtFeedItem(kind = YtFeedItem.KIND_PLAYLIST, id = "PL1", title = "PL1"),
            video("music1", music = true),
            // A Mix (RD… radio) outranks everything.
            YtFeedItem(kind = YtFeedItem.KIND_PLAYLIST, id = "RDEM1", title = "Mix"),
            video("artist1", badge = YtFeedItem.OWNER_BADGE_ARTIST),
            video("plain2"),
            // A music video from an artist channel still ranks in the music band.
            video("music2", music = true, badge = YtFeedItem.OWNER_BADGE_ARTIST),
            video("verified2", badge = YtFeedItem.OWNER_BADGE_VERIFIED),
        )
        assertEquals(
            listOf("RDEM1", "music1", "PL1", "music2", "artist1", "verified1", "verified2", "plain1", "plain2"),
            rankMusicFirst(items).map { it.id },
        )
    }

    @Test
    fun untagged_feed_keeps_its_order() {
        val items = listOf(video("a"), video("b"), YtFeedItem(kind = YtFeedItem.KIND_CHANNEL, id = "UC1", title = "c"))
        assertEquals(listOf("a", "b", "UC1"), rankMusicFirst(items).map { it.id })
    }
}
