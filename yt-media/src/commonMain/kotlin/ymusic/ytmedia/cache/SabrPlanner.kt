package ymusic.ytmedia.cache

import okio.Buffer
import okio.Source
import okio.buffer
import okio.use
import ymusic.ytmedia.sabr.BufferedRange
import ymusic.ytmedia.sabr.FormatId
import ymusic.ytmedia.sabr.MediaHeader
import ymusic.ytmedia.sabr.NextRequestPolicy
import ymusic.ytmedia.sabr.VideoPlaybackAbrRequest
import ymusic.ytmedia.sabr.formatCacheKey
import ymusic.ytmedia.ump.UmpPart
import ymusic.ytmedia.ump.writeUmpPartHeader

/** What the cache can do for a SABR request (plan §3c). */
sealed class SabrServe {
    /** A synthesized UMP body of cached segments — serve it to the requester with no network. */
    class Hit(val ump: Source, val cachedBytes: Long, val segmentCount: Int) : SabrServe()

    /** The requester is already buffered far enough ahead; a policy-only body tells it to back off. */
    class Backoff(val ump: Source) : SabrServe()

    /** Nothing useful cached — go to the network. */
    data object Miss : SabrServe()
}

// Readahead windows, ported from legacy SabrCacheHandler. MIN is the "keep filling until covered" target;
// TARGET is what the synthesized NextRequestPolicy advertises back.
private const val TARGET_AUDIO_READAHEAD_MS = 60_000
private const val MIN_AUDIO_READAHEAD_MS = TARGET_AUDIO_READAHEAD_MS + 45_000L
private const val TARGET_VIDEO_READAHEAD_MS = 60_000
private const val MIN_VIDEO_READAHEAD_MS = TARGET_VIDEO_READAHEAD_MS + 45_000L

/**
 * Plan a local UMP response for a SABR request from the cached segments (port of
 * `SabrCacheHandler.handleSabrRequest`). Walks [SegmentIndex] forward from the request's `playerTimeMs`
 * until the readahead window is covered, **verifying every segment against `blobs.isCached`**, prepends
 * each format's init segment, then re-acquires the bytes with `blobs.read` (plan-then-reacquire: any span
 * lost to eviction in the gap collapses the whole serve to [SabrServe.Miss] → network). A fully-buffered
 * requester gets a [SabrServe.Backoff]; nothing cached gets [SabrServe.Miss].
 *
 * The downloader passes its own request here first so a re-download reuses the cache; the player
 * interception layer (later) passes the intercepted request the same way.
 */
suspend fun MediaCache.serveSabr(request: VideoPlaybackAbrRequest): SabrServe {
    val abr = request.clientAbrState
    val playerTimeMs = abr.playerTimeMs
    val isAudioOnly = abr.enabledTrackTypesBitfield == 1
    val byCacheKey = request.adaptiveFormats.associateBy { it.cacheKey }
    val itagMime = request.adaptiveFormats.associate { it.itag to it.mimeTypeWithoutCodec }
    val selectedMimes = request.selectedFormats.mapNotNull { itagMime[it.itag] }.toSet()

    // Prefer webm, then formats the client already selected, then (if DRC) DRC formats. When the request
    // carries no adaptiveFormats (e.g. our own downloader), every score is 0 and the given order stands.
    fun score(f: FormatId): Int {
        var s = 0
        val mime = itagMime[f.itag] ?: ""
        if ("webm" in mime) s += 1
        if (mime in selectedMimes) s += 2
        if (abr.drcEnabled && byCacheKey[f.cacheKey]?.isDrc == true) s += 4
        return s
    }

    var audioFormats = request.selectedAudioFormats.sortedByDescending(::score)
    audioFormats = narrowAudioFormats(audioFormats, request, byCacheKey)

    val audioItags = request.selectedAudioFormats.map { it.itag }.toSet()
    val audioBuffered = request.bufferedRanges.filter { it.formatId.itag in audioItags }
    val audio = findCachedSegments(playerTimeMs, audioFormats, audioBuffered, MIN_AUDIO_READAHEAD_MS)

    var video: CheckResult = CheckResult.Miss
    if (!isAudioOnly) {
        val videoFormats = request.selectedVideoFormats.sortedByDescending { f ->
            var s = byCacheKey[f.cacheKey]?.let { minOf(it.width ?: 0, it.height ?: 0) } ?: 0
            val mime = itagMime[f.itag] ?: ""
            if ("webm" in mime) s += 10_000
            if (mime in selectedMimes) s += 20_000
            s
        }
        val videoItags = request.selectedVideoFormats.map { it.itag }.toSet()
        val videoBuffered = request.bufferedRanges.filter { it.formatId.itag in videoItags }
        video = findCachedSegments(playerTimeMs, videoFormats, videoBuffered, MIN_VIDEO_READAHEAD_MS)
    }

    val segments = buildList {
        (audio as? CheckResult.Cached)?.let { addAll(it.segments) }
        (video as? CheckResult.Cached)?.let { addAll(it.segments) }
    }.sortedBy { it.startMs ?: -1 }

    if (segments.isEmpty()) {
        // Both tracks already buffered far enough → tell the requester to back off rather than refetch.
        if (audio is CheckResult.Buffered && (isAudioOnly || video is CheckResult.Buffered)) {
            var backoff = audio.bufferedPosMs - playerTimeMs
            if (video is CheckResult.Buffered) backoff = minOf(backoff, video.bufferedPosMs - playerTimeMs)
            if (backoff - minOf(TARGET_AUDIO_READAHEAD_MS.toLong(), TARGET_VIDEO_READAHEAD_MS.toLong()) > 2000) {
                val policy = NextRequestPolicy(
                    targetAudioReadaheadMs = TARGET_AUDIO_READAHEAD_MS,
                    targetVideoReadaheadMs = TARGET_VIDEO_READAHEAD_MS,
                    backoffTimeMs = 2000,
                    playbackCookie = request.streamerContext.playbackCookie,
                    videoId = null,
                )
                return SabrServe.Backoff(synthesizeUmp(policy, emptyList()) ?: Buffer())
            }
        }
        return SabrServe.Miss
    }

    val policy = NextRequestPolicy(
        targetAudioReadaheadMs = TARGET_AUDIO_READAHEAD_MS,
        targetVideoReadaheadMs = TARGET_VIDEO_READAHEAD_MS,
        backoffTimeMs = null,
        playbackCookie = request.streamerContext.playbackCookie,
        videoId = null,
    )
    // Re-acquire every segment's bytes; a miss here (evicted in the plan→serve gap) means network.
    val ump = synthesizeUmp(policy, segments) ?: return SabrServe.Miss
    val bytes = segments.sumOf { it.contentLength }
    return SabrServe.Hit(ump, bytes, segments.size)
}

/** Legacy multi-audio narrowing: honor a requested track id, DRC preference, then the default track. */
private fun narrowAudioFormats(
    formats: List<FormatId>,
    request: VideoPlaybackAbrRequest,
    byCacheKey: Map<String, ymusic.ytmedia.sabr.AdaptiveFormat>,
): List<FormatId> {
    val requestedTrack = request.clientAbrState.requestAudioTrackId
    if (requestedTrack == null && formats.size <= 1) return formats
    var out = formats
    if (requestedTrack != null) {
        val f = out.filter { byCacheKey[it.cacheKey]?.audioTrackId == requestedTrack }
        if (f.isNotEmpty()) out = f
    }
    if (request.clientAbrState.drcEnabled && out.size > 1) {
        val f = out.filter { byCacheKey[it.cacheKey]?.isDrc == true }
        if (f.isNotEmpty()) out = f
    }
    if (out.size > 1) {
        val def = out.firstOrNull { byCacheKey[it.cacheKey]?.audioTrackIsDefault == true }
            ?: out.firstOrNull { fmt -> request.selectedFormats.any { it.cacheKey == fmt.cacheKey } }
        if (def != null) out = listOf(def)
    }
    return out
}

private sealed class CheckResult {
    data object Miss : CheckResult()
    class Buffered(val bufferedPosMs: Long) : CheckResult()
    class Cached(val bufferedPosMs: Long, val segments: List<MediaHeader>) : CheckResult()
}

/**
 * Walk cached segments of the first covered format forward from [playerTimeMs] until [minBufferMs] is
 * covered, prepending each format's init segment on first use. Every candidate is verified against
 * `blobs.isCached`. Port of legacy `findFormatCachedSegments`.
 */
private suspend fun MediaCache.findCachedSegments(
    playerTimeMs: Long,
    formats: List<FormatId>,
    bufferedRanges: List<BufferedRange>,
    minBufferMs: Long,
): CheckResult {
    val sorted = bufferedRanges.sortedBy { it.startSegmentIndex }
    val initialized = bufferedRanges.map { it.formatId }.toMutableSet()
    val result = mutableListOf<MediaHeader>()

    suspend fun addNext(find: suspend (FormatId) -> MediaHeader?): MediaHeader? {
        for (format in formats) {
            val segment = find(format) ?: continue
            if (!blobs.isCached(segment.cacheKey, segment.startRange, segment.contentLength)) continue
            if (format !in initialized) {
                val init = index.findBySegmentIndex(format.itag, format.lmt, 0) ?: continue
                if (!blobs.isCached(init.cacheKey, init.startRange, init.contentLength)) continue
                result += init
                initialized += format
            }
            result += segment
            return segment
        }
        return null
    }

    var bufferedPosMs = -1L
    var nextSeg: Int
    var newBufferedPos: Long
    val insideBuffered = sorted.firstOrNull { it.startMs <= playerTimeMs && playerTimeMs < it.endMs }
    if (insideBuffered != null) {
        nextSeg = insideBuffered.startSegmentIndex
        sorted.forEach {
            if (it.startSegmentIndex <= nextSeg && it.endSegmentIndex >= nextSeg) {
                nextSeg = it.endSegmentIndex + 1
                bufferedPosMs = it.startMs + it.durationMs - 1
            }
        }
        newBufferedPos = bufferedPosMs
    } else {
        val seg = addNext { index.findByStartTime(it.itag, it.lmt, playerTimeMs) } ?: return CheckResult.Miss
        nextSeg = seg.segmentIndex + 1
        newBufferedPos = checkNotNull(seg.startMs) + checkNotNull(seg.durationMs) - 1
    }

    while (newBufferedPos - playerTimeMs < minBufferMs) {
        if (sorted.any { it.startSegmentIndex <= nextSeg && it.endSegmentIndex >= nextSeg }) break
        val seg = addNext { index.findBySegmentIndex(it.itag, it.lmt, nextSeg) } ?: break
        nextSeg++
        newBufferedPos = checkNotNull(seg.startMs) + checkNotNull(seg.durationMs) - 1
    }

    if (result.isEmpty()) {
        return if (bufferedPosMs - playerTimeMs >= minBufferMs) CheckResult.Buffered(bufferedPosMs) else CheckResult.Miss
    }
    return CheckResult.Cached(bufferedPosMs, result)
}

/**
 * Build a UMP body: a NEXT_REQUEST_POLICY part followed by MEDIA_HEADER / MEDIA_DATA / MEDIA_END for each
 * segment, streaming its bytes out of the blob store. Returns null if any segment's bytes can't be
 * re-acquired (evicted since planning) — the caller then treats the whole serve as a network miss.
 */
private suspend fun MediaCache.synthesizeUmp(policy: NextRequestPolicy, segments: List<MediaHeader>): Buffer? {
    val out = Buffer()
    val policyBytes = policy.encode().toByteArray()
    out.writeUmpPartHeader(UmpPart.NEXT_REQUEST_POLICY, policyBytes.size.toLong()).write(policyBytes)

    segments.forEachIndexed { i, segment ->
        val headerId = i and 0xFF
        val pinned = blobs.read(segment.cacheKey, segment.startRange, segment.contentLength) ?: return null
        try {
            val headerBytes = segment.copy(headerId = headerId).encode().toByteArray()
            out.writeUmpPartHeader(UmpPart.MEDIA_HEADER, headerBytes.size.toLong()).write(headerBytes)
            out.writeUmpPartHeader(UmpPart.MEDIA_DATA, segment.contentLength + 1).writeByte(headerId)
            pinned.buffer().use { it.readAll(out) }
            out.writeUmpPartHeader(UmpPart.MEDIA_END, 1).writeByte(headerId)
        } catch (e: Throwable) {
            pinned.close()
            throw e
        }
    }
    return out
}

/**
 * Serve a progressive `/videoplayback` range read `bytes=[rangeStart, rangeEnd]` (inclusive, HTTP
 * semantics) straight from the byte store — the cross-protocol payoff (plan §1): bytes cached by *any*
 * protocol (a SABR fill, a prior progressive fill) answer a progressive range because within a format
 * stream segments are contiguous in byte space. Returns the pinned slice, or null (→ network) if the
 * range isn't fully cached right now. `rangeEnd < 0` means open-ended, which the cache never fully
 * covers, so it's always a miss here (let the network stream it).
 */
suspend fun MediaCache.serveProgressive(itag: Int, lmt: Long, rangeStart: Long, rangeEnd: Long): PinnedSource? {
    if (rangeEnd < rangeStart) return null
    val length = rangeEnd - rangeStart + 1
    return blobs.read(formatCacheKey(itag, lmt), rangeStart, length)
}

// NB: fillFromProgressive (teeing a progressive network body into the cache) lands with the platform
// interception layer (step 6) — it needs a coroutine scope to abort its writer on a cancelled stream,
// which the interception plumbing provides. Downloads already fill the cache via fillFromSabr.

/** The cache keys of every stream with committed bytes — the `keepKeys` set for `SegmentIndex.cleanup`. */
suspend fun MediaCache.liveKeys(): Set<String> = blobs.keys()

/** Convenience for callers that hold (itag, lmt) rather than a key. */
fun cacheKeyOf(itag: Int, lmt: Long): String = formatCacheKey(itag, lmt)
