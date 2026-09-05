package io.ymusic.player

import io.ymusic.player.webview.WebViewEvent
import kotlinx.coroutines.flow.SharedFlow

/**
 * YouTube playback engine. Same capabilities the facade binds ([VideoQualitySelectable], captions,
 * audio tracks) plus download helpers and the watch-next harvest. [io.ymusic.player.mse.MsePlayer]
 * is the playback implementation; [WebViewPlayer] remains for SABR download cooking.
 */
interface YtEngine :
    MediaEngine<PlayableItem.YtMedia>,
    VideoQualitySelectable,
    CaptionSelectable,
    CaptionRenderable,
    AudioTrackSelectable {
    val watchNextSeen: SharedFlow<WebViewEvent.WatchNextSeen>
    fun refreshOnNext()
    fun warmUp()
    suspend fun requestPlayerResponse(videoId: String): String
    suspend fun requestPoToken(videoId: String): String
}
