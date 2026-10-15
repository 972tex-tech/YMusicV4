package io.ymusic.android

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import io.ymusic.player.Player
import io.ymusic.settings.AppSettings
import io.ymusic.settings.SettingsStore
import io.ymusic.settings.flow
import kmpcommon.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * [AppSettings.systemAudioEffects]: while on, the playing engine's audio session is announced to
 * equalizer apps (AudioEffect control-session open/close broadcasts) and the in-app effects are bypassed.
 */
class SystemAudioEffects(
    private val context: Context,
    private val player: Player,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val log: Logger,
) {
    private var openSession = 0

    fun start() {
        val enabled = settings.flow(AppSettings.systemAudioEffects)
        scope.launch {
            enabled.collect { player.setInAppAudioFx(!it) }
        }
        scope.launch {
            combine(enabled, player.audioSessionId) { on, session -> if (on) session else 0 }
                .distinctUntilChanged()
                .collect { session ->
                    if (openSession != 0) broadcast(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION, openSession)
                    openSession = session
                    if (session != 0) broadcast(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, session)
                }
        }
    }

    private fun broadcast(action: String, session: Int) {
        log.log(Logger.Level.INFO, "$action session=$session")
        context.sendBroadcast(
            Intent(action)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
        )
    }
}
