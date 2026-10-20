package io.ymusic.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

internal const val ACTION_PLAY_FROM_SEARCH = "io.ymusic.android.playback.PLAY_FROM_SEARCH"

/**
 * The phone's MEDIA_PLAY_FROM_SEARCH target (Gemini / Assistant "play Eminem on YMusic"): no UI,
 * hands the query and focus extras to [PlaybackService], which resolves and plays them. Android
 * Auto delivers the same request through the media session instead.
 */
class VoiceSearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forward = Intent(this, PlaybackService::class.java).setAction(ACTION_PLAY_FROM_SEARCH)
        intent.extras?.let { forward.putExtras(it) }
        ContextCompat.startForegroundService(this, forward)
        finish()
    }
}
