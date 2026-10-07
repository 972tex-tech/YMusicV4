package io.ymusic.ytmedia.cache

import app.cash.sqldelight.db.SqlDriver
import io.ymusic.ytmedia.db.SegmentDatabase
import kmpcommon.WorkerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Lifetime player-cache counters. [hitBytes] is every byte served from a cache hit. */
interface CacheStats {
    val hitBytes: StateFlow<Long>

    @WorkerThread
    fun addHitBytes(bytes: Long)
}

/** [CacheStats] persisted in the cache-index DB ([SegmentDatabase.Schema]). Reads the stored total on construction. */
@WorkerThread
class SqlDelightCacheStats(driver: SqlDriver) : CacheStats {
    private val queries = SegmentDatabase(driver).cacheStatsQueries
    private val total = MutableStateFlow(
        queries.run {
            ensureRow()
            hitBytes().executeAsOne()
        },
    )

    override val hitBytes: StateFlow<Long> = total

    override fun addHitBytes(bytes: Long) {
        if (bytes <= 0) return
        queries.addHitBytes(bytes)
        total.value = queries.hitBytes().executeAsOne()
    }
}
