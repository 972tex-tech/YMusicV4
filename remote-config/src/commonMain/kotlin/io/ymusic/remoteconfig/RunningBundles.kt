package io.ymusic.remoteconfig

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The JS bundle revisions this process is running, sent with every service call as
 * `x-engine-js-ver` / `x-player-js-ver` and shown in Settings. The shells move [engine] on each engine
 * boot or remote swap and [player] each time a player session starts; null until that bundle has run.
 */
class RunningBundles {
    private val engineState = MutableStateFlow<Int?>(null)
    private val playerState = MutableStateFlow<Int?>(null)

    val engineFlow: StateFlow<Int?> get() = engineState
    val playerFlow: StateFlow<Int?> get() = playerState

    var engine: Int?
        get() = engineState.value
        set(value) {
            engineState.value = value
        }

    var player: Int?
        get() = playerState.value
        set(value) {
            playerState.value = value
        }
}
