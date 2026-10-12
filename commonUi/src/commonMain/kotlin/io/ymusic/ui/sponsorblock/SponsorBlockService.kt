package io.ymusic.ui.sponsorblock

import io.ymusic.settings.AppSettings
import io.ymusic.settings.SettingsStore
import io.ymusic.settings.flow
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

enum class SponsorBlockState {
    Unavailable,
    Available,
    Whitelisted,
}

data class SponsorBlockSnapshot(
    val segments: List<SponsorBlockSegment>,
    val state: SponsorBlockState,
)

/**
 * Cache + whitelist + enable gate over [SponsorBlockApi]. Empty skip list when globally off or the
 * video is whitelisted. Cache younger than 6 minutes is reused; a fetch error falls back to a
 * stale cache when one exists.
 */
class SponsorBlockService(
    private val api: SponsorBlockApi?,
    private val store: SponsorBlockStore,
    private val settings: SettingsStore,
    logger: Logger? = null,
) {
    private val log = logger?.fork("sponsor-block")
    private val mutexPerVideo = MutexPerKey<String>()
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    suspend fun load(videoId: String): SponsorBlockSnapshot = mutexPerVideo.withLock(videoId) {
        val enabled = settings.flow(AppSettings.sponsorBlockEnabled).value
        val whitelisted = store.isWhitelisted(videoId)
        if (!enabled || whitelisted) {
            return@withLock SponsorBlockSnapshot(
                emptyList(),
                if (enabled && whitelisted) SponsorBlockState.Whitelisted else SponsorBlockState.Unavailable,
            )
        }
        val cached = try {
            store.getCached(videoId)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            log?.log(Logger.Level.WARN, "cache read failed", e)
            null
        }
        val now = nowMs()
        if (cached != null && now - cached.updatedAt < CACHE_TTL_MS) {
            return@withLock snapshotOf(cached.segments)
        }
        if (api == null) return@withLock snapshotOf(cached?.segments.orEmpty())
        try {
            val response = api.getSkipSegments(videoId)
            try {
                store.cache(videoId, response.segments)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                log?.log(Logger.Level.WARN, "cache write failed", e)
            }
            snapshotOf(response.segments)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            log?.log(Logger.Level.WARN, "getSkipSegments failed", e)
            snapshotOf(cached?.segments.orEmpty())
        }
    }

    suspend fun addToWhitelist(videoId: String) {
        if (store.addToWhitelist(videoId)) notifyChanged()
    }

    suspend fun removeFromWhitelist(videoId: String) {
        if (store.removeFromWhitelist(videoId)) notifyChanged()
    }

    private fun snapshotOf(segments: List<SponsorBlockSegment>): SponsorBlockSnapshot =
        SponsorBlockSnapshot(
            segments,
            if (segments.isNotEmpty()) SponsorBlockState.Available else SponsorBlockState.Unavailable,
        )

    private fun notifyChanged() {
        _changes.update { it + 1 }
    }

    private companion object {
        val CACHE_TTL_MS = 6.minutes.inWholeMilliseconds
    }
}

private class MutexPerKey<K : Any> {
    private val map = mutableMapOf<K, Mutex>()
    private val guard = Mutex()

    suspend fun <T> withLock(key: K, block: suspend () -> T): T {
        val mutex = guard.withLock { map.getOrPut(key) { Mutex() } }
        return mutex.withLock { block() }
    }
}

@OptIn(ExperimentalTime::class)
private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
