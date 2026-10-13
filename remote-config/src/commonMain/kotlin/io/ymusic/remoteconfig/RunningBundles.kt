package io.ymusic.remoteconfig

import kotlin.concurrent.Volatile

/**
 * The JS bundle revisions this process is running, sent with every service call as
 * `x-engine-js-ver` / `x-player-js-ver`. The shells move [engine] on each engine boot or remote swap
 * and [player] each time a player session starts; null until that bundle has run.
 */
class RunningBundles {
    @Volatile
    var engine: Int? = null

    @Volatile
    var player: Int? = null
}
