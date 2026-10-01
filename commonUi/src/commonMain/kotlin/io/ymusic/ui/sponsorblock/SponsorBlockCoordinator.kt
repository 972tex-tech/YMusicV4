package io.ymusic.ui.sponsorblock

import io.ymusic.player.PlayableItem
import io.ymusic.player.SkipSegment
import io.ymusic.settings.AppSettings
import io.ymusic.settings.flow
import io.ymusic.ui.AppGraph
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Watches the current queue item and arms [io.ymusic.player.Player.setSkipSegments] from
 * [AppGraph.sponsorBlock]. Local items have no video id, so they clear. Reloads when the
 * enable setting or per-video whitelist changes.
 */
class SponsorBlockCoordinator(
    private val graph: AppGraph,
    scope: CoroutineScope,
) {
    private val log = graph.logger?.fork("sponsor-block")

    init {
        scope.launch {
            var fetchJob: Job? = null
            combine(
                graph.player.queue.map { it.currentItem }.distinctUntilChanged(),
                graph.sponsorBlock.changes,
                graph.settings.flow(AppSettings.sponsorBlockEnabled),
            ) { item, _, _ -> item }
                .collect { item ->
                    fetchJob?.cancel()
                    val videoId = (item as? PlayableItem.YtMedia)?.videoId
                    if (item == null || videoId == null) {
                        graph.player.clearSkipSegments()
                    } else {
                        fetchJob = launch {
                            val snapshot = try {
                                graph.sponsorBlock.load(videoId)
                            } catch (c: CancellationException) {
                                throw c
                            } catch (e: Exception) {
                                log?.log(Logger.Level.WARN, "getSkipSegments failed", e)
                                SponsorBlockSnapshot(emptyList(), SponsorBlockState.Unavailable)
                            }
                            if (graph.player.queue.value.currentItem == item) {
                                graph.player.setSkipSegments(item, snapshot.segments.toPlayerSkipSegments())
                            }
                        }
                    }
                }
        }
    }
}

internal fun List<SponsorBlockSegment>.toPlayerSkipSegments(): List<SkipSegment> =
    mapNotNull { seg ->
        val lengthMs = ((seg.endTime - seg.startTime) * 1_000.0).toLong()
        if (lengthMs < 1_000L) {
            null
        } else {
            SkipSegment(
                startMs = (seg.startTime * 1_000.0).toLong(),
                lengthMs = lengthMs,
            )
        }
    }
