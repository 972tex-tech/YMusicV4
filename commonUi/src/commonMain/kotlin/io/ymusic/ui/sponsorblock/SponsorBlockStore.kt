package io.ymusic.ui.sponsorblock

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

data class CachedSkipSegments(
    val segments: List<SponsorBlockSegment>,
    val updatedAt: Long,
)

interface SponsorBlockStore {
    suspend fun getCached(videoId: String): CachedSkipSegments?

    suspend fun cache(videoId: String, segments: List<SponsorBlockSegment>)

    suspend fun isWhitelisted(videoId: String): Boolean

    /** True when [videoId] was newly added. */
    suspend fun addToWhitelist(videoId: String): Boolean

    /** True when [videoId] was present and removed. */
    suspend fun removeFromWhitelist(videoId: String): Boolean
}

/** In-process stand-in used by tests and shells that have not wired a database yet. */
class InMemorySponsorBlockStore : SponsorBlockStore {
    private val cache = mutableMapOf<String, CachedSkipSegments>()
    private val whitelist = mutableSetOf<String>()

    override suspend fun getCached(videoId: String): CachedSkipSegments? = cache[videoId]

    override suspend fun cache(videoId: String, segments: List<SponsorBlockSegment>) {
        cache[videoId] = CachedSkipSegments(segments, nowMs())
    }

    override suspend fun isWhitelisted(videoId: String): Boolean = videoId in whitelist

    override suspend fun addToWhitelist(videoId: String): Boolean = whitelist.add(videoId)

    override suspend fun removeFromWhitelist(videoId: String): Boolean = whitelist.remove(videoId)
}

/** SQLite port of legacy `SponsorBlockService.Database`. */
class SqliteSponsorBlockStore(
    private val driver: SqlDriver,
) : SponsorBlockStore {
    private val io = sponsorBlockDispatcher()
    private var writes = 0

    override suspend fun getCached(videoId: String): CachedSkipSegments? = withContext(io) {
        val row = driver.executeQuery(
            null,
            "SELECT segments, updatedAt FROM sponsorBlockCache WHERE videoId = ?",
            { cursor ->
                if (!cursor.next().value) {
                    QueryResult.Value(null)
                } else {
                    val segments = decodeSkipSegments(cursor.getString(0) ?: "")
                    val updatedAt = cursor.getLong(1)
                    QueryResult.Value(
                        if (segments == null || updatedAt == null) {
                            null
                        } else {
                            CachedSkipSegments(segments, updatedAt)
                        },
                    )
                }
            },
            1,
        ) { bindString(0, videoId) }.value ?: return@withContext null
        touchUsedAt(videoId)
        row
    }

    override suspend fun cache(videoId: String, segments: List<SponsorBlockSegment>) = withContext(io) {
        val now = nowMs()
        val json = encodeSkipSegments(segments)
        // INSERT OR IGNORE + UPDATE, not an upsert: ON CONFLICT … DO UPDATE needs SQLite 3.24 (Android 11+).
        driver.execute(
            null,
            "INSERT OR IGNORE INTO sponsorBlockCache(videoId, segments, updatedAt, usedAt) VALUES (?, ?, ?, ?)",
            4,
        ) {
            bindString(0, videoId)
            bindString(1, json)
            bindLong(2, now)
            bindLong(3, now)
        }
        driver.execute(
            null,
            "UPDATE sponsorBlockCache SET segments = ?, updatedAt = ?, usedAt = ? WHERE videoId = ?",
            4,
        ) {
            bindString(0, json)
            bindLong(1, now)
            bindLong(2, now)
            bindString(3, videoId)
        }
        writes++
        if (writes % 15 == 0) cleanUpUnused()
    }

    override suspend fun isWhitelisted(videoId: String): Boolean = withContext(io) {
        driver.executeQuery(
            null,
            "SELECT 1 FROM sponsorBlockWhiteList WHERE videoId = ? LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) },
            1,
        ) { bindString(0, videoId) }.value
    }

    override suspend fun addToWhitelist(videoId: String): Boolean = withContext(io) {
        if (isWhitelistedNow(videoId)) return@withContext false
        driver.execute(
            null,
            "INSERT OR IGNORE INTO sponsorBlockWhiteList(videoId) VALUES (?)",
            1,
        ) { bindString(0, videoId) }
        true
    }

    override suspend fun removeFromWhitelist(videoId: String): Boolean = withContext(io) {
        if (!isWhitelistedNow(videoId)) return@withContext false
        driver.execute(
            null,
            "DELETE FROM sponsorBlockWhiteList WHERE videoId = ?",
            1,
        ) { bindString(0, videoId) }
        true
    }

    private fun isWhitelistedNow(videoId: String): Boolean =
        driver.executeQuery(
            null,
            "SELECT 1 FROM sponsorBlockWhiteList WHERE videoId = ? LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) },
            1,
        ) { bindString(0, videoId) }.value

    private fun touchUsedAt(videoId: String) {
        driver.execute(
            null,
            "UPDATE sponsorBlockCache SET usedAt = ? WHERE videoId = ?",
            2,
        ) {
            bindLong(0, nowMs())
            bindString(1, videoId)
        }
    }

    private fun cleanUpUnused() {
        val cutoff = nowMs() - UNUSED_CACHE_MS
        driver.execute(
            null,
            "DELETE FROM sponsorBlockCache WHERE usedAt < ?",
            1,
        ) { bindLong(0, cutoff) }
    }

    private companion object {
        val UNUSED_CACHE_MS = 20.days.inWholeMilliseconds
    }
}

@OptIn(ExperimentalTime::class)
private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
