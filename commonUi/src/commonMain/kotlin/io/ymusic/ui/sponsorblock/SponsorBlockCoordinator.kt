package io.ymusic.ui.sponsorblock

import io.ymusic.player.PlayableItem
import io.ymusic.player.SkipSegment
import io.ymusic.ui.AppGraph
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Watches the current queue item and arms [io.ymusic.player.Player.setSkipSegments] from
 * [AppGraph.sponsorBlock]. Local items have no video id, so they clear.
 */
class SponsorBlockCoordinator(
    private val graph: AppGraph,
    scope: CoroutineScope,
) {
    private val log = graph.logger?.fork("sponsor-block")

    init {
        scope.launch {
            var fetchJob: Job? = null
            graph.player.queue
                .map { it.currentItem }
                .distinctUntilChanged()
                .collect { item ->
                    fetchJob?.cancel()
                    val videoId = (item as? PlayableItem.YtMedia)?.videoId
                    if (item == null || videoId == null) {
                        graph.player.clearSkipSegments()
                    } else {
                        fetchJob = launch {
                            val segments = try {
                                graph.sponsorBlock.getSkipSegments(videoId).toPlayerSkipSegments()
                            } catch (c: CancellationException) {
                                throw c
                            } catch (e: Exception) {
                                log?.log(Logger.Level.WARN, "getSkipSegments failed", e)
                                emptyList()
                            }
                            if (graph.player.queue.value.currentItem == item) {
                                graph.player.setSkipSegments(item, segments)
                            }
                        }
                    }
                }
        }
    }
}

internal fun GetSkipSegmentsResponse.toPlayerSkipSegments(): List<SkipSegment> =
    segments.mapNotNull { seg ->
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
