package io.ymusic.download

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Paces player-response fetches: one at a time, each starting at least [interval] after the previous one
 * started. Every fetch drives the app's single YouTube engine (and hits /player), so a 100-video queue
 * must not hammer it — downloads, re-attests and the options probe all pass through one gate.
 */
internal class PlayerResponseGate(private val interval: Duration = 3.seconds) {
    private val mutex = Mutex()
    private var lastStart: TimeMark? = null

    suspend fun <T> run(fetch: suspend () -> T): T = mutex.withLock {
        val sinceLast = lastStart?.elapsedNow()
        if (sinceLast != null && sinceLast < interval) delay(interval - sinceLast)
        lastStart = TimeSource.Monotonic.markNow()
        fetch()
    }
}
