package ymusic.ytmedia.cache

import kmpcommon.WorkerThread
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.Sink
import okio.Timeout
import okio.buffer

/**
 * okio-`FileSystem`-backed [MediaBlobCache]. Committed bytes live in **immutable span files**
 * `{root}/{key}/{start}_{len}.span`; an in-memory index (`key → spans`, populated from the dir on first
 * touch of a key) tracks their ranges, `lastUsed`, and read pins. The index, pin counts, in-flight write
 * set and totals are guarded by one [lock] — small, IO-free critical sections — so calls from multiple
 * worker threads (and the synchronous [PinnedSource.close]) are safe.
 *
 * This reproduces media3 `SimpleCache`'s six concurrency guarantees without media3 (see
 * `docs/player/sabr-cache-plan.md` §3a):
 *
 * 1. **Atomic commit / immutable spans** — a block streams to a `.tmp`, then `atomicMove`s to its
 *    `{start}_{len}.span`. Readers only open committed `.span` files, so a partial write is never visible.
 * 2. **Per-block commit** — a region is cut into blocks of ≤[maxSpanBytes]; each commits independently, so
 *    an interrupted write keeps the blocks it finished (valid bytes for their ranges) and a retry resumes.
 * 3. **Single-writer-per-region dedupe** — an in-flight set keyed `(key, start, len)`; [writer] hands back
 *    a no-op sink when the exact region is already committed or in flight. Segment ranges are
 *    deterministic (the same bytes are always requested with the same `(start, len)`), so exact-range
 *    keying suffices; the cache dir is single-instance, as media3 also assumed.
 * 4. **Read pinning** — [read] ref-count-pins its covering spans; eviction never touches a pinned span.
 *    Portable, unlike media3's reliance on POSIX unlink-after-open.
 * 5. **Plan-then-reacquire** — [isCached] is a hint; [read] re-checks coverage under the lock and returns
 *    null (→ network) on any gap, holding no lock across plan→serve, so races resolve to a miss.
 * 6. **LRU eviction** — on commit, evict least-recently-used **unpinned** spans until total ≤ [budgetBytes].
 */
@WorkerThread
class DiskMediaBlobCache(
    private val root: Path,
    private val fileSystem: FileSystem,
    private val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    private val maxSpanBytes: Long = DEFAULT_MAX_SPAN_BYTES,
) : MediaBlobCache {

    private class Span(val start: Long, val len: Long, val path: Path, var lastUsed: Long, var pins: Int) {
        val end get() = start + len
    }

    /** One contiguous slice to read from one span file: `[skip, skip+take)` within [path]. */
    private class Chunk(val span: Span, val path: Path, val skip: Long, val take: Long)

    private val lock = SynchronizedObject()
    private val index = mutableMapOf<String, MutableList<Span>>()
    private val loadedKeys = mutableSetOf<String>()
    private val inFlight = mutableSetOf<String>()
    private var totalBytes = 0L
    private var clock = 0L // monotonic LRU tick; only order matters, not wall time

    override fun isCached(key: String, position: Long, length: Long): Boolean {
        ensureLoaded(key)
        return synchronized(lock) { cover(index[key], position, length) != null }
    }

    override fun read(key: String, position: Long, length: Long): PinnedSource? {
        ensureLoaded(key)
        val chunks = synchronized(lock) {
            val plan = cover(index[key], position, length) ?: return@synchronized null
            val tick = ++clock
            plan.forEach { it.span.pins++; it.span.lastUsed = tick } // pin + LRU-touch atomically
            plan
        } ?: return null
        return StitchedSource(chunks)
    }

    override fun writer(key: String, position: Long, length: Long): BlobWriter {
        ensureLoaded(key)
        val token = flightToken(key, position, length)
        val real = synchronized(lock) {
            val taken = cover(index[key], position, length) != null || token in inFlight
            if (!taken) inFlight += token
            !taken
        }
        return if (real) RealBlobWriter(key, position, token) else NoOpBlobWriter()
    }

    override fun keys(): Set<String> {
        loadAllKeys()
        return synchronized(lock) { index.filterValues { it.isNotEmpty() }.keys.toSet() }
    }

    override fun totalSize(): Long {
        loadAllKeys()
        return synchronized(lock) { totalBytes }
    }

    // --- covering (greedy max-reach; tolerates overlapping spans) ------------------------------------

    /** Chunks exactly covering `[position, position+length)`, or null on any gap. Call under [lock]. */
    private fun cover(spans: List<Span>?, position: Long, length: Long): List<Chunk>? {
        if (length <= 0) return emptyList()
        if (spans.isNullOrEmpty()) return null
        val end = position + length
        var cursor = position
        val chunks = ArrayList<Chunk>()
        while (cursor < end) {
            // Of the spans covering `cursor`, take the one reaching furthest: fewest files, tolerates overlap.
            val best = spans.filter { it.start <= cursor && it.end > cursor }.maxByOrNull { it.end } ?: return null
            val take = minOf(best.end - cursor, end - cursor)
            chunks += Chunk(best, best.path, skip = cursor - best.start, take = take)
            cursor += take
        }
        return chunks
    }

    // --- index loading (IO outside the lock, merge under it) -----------------------------------------

    private fun ensureLoaded(key: String) {
        if (synchronized(lock) { key in loadedKeys }) return
        val dir = root / key
        val scanned = mutableListOf<Triple<Long, Long, Path>>()
        if (fileSystem.exists(dir)) {
            for (path in fileSystem.list(dir)) {
                if (path.name.endsWith(".$TMP_EXT")) {
                    fileSystem.delete(path, mustExist = false) // sweep a crashed writer's temp
                    continue
                }
                parseSpanName(path.name)?.let { (start, len) -> scanned += Triple(start, len, path) }
            }
        }
        synchronized(lock) {
            if (key in loadedKeys) return // another coroutine scanned while we did
            loadedKeys += key
            index[key] = scanned.mapTo(mutableListOf()) { (start, len, path) ->
                totalBytes += len
                Span(start, len, path, lastUsed = ++clock, pins = 0)
            }
        }
    }

    private fun loadAllKeys() {
        if (!fileSystem.exists(root)) return
        for (dir in fileSystem.list(root)) {
            if (fileSystem.metadataOrNull(dir)?.isDirectory == true) ensureLoaded(dir.name)
        }
    }

    // --- eviction ------------------------------------------------------------------------------------

    /** Under [lock], drop unpinned LRU spans until total+incoming ≤ budget; returns their paths to delete. */
    private fun planEviction(incoming: Long): List<Path> {
        if (totalBytes + incoming <= budgetBytes) return emptyList()
        val victims = ArrayList<Path>()
        val candidates = index.values.flatten().filter { it.pins == 0 }.sortedBy { it.lastUsed }
        var freed = 0L
        for (span in candidates) {
            if (totalBytes - freed + incoming <= budgetBytes) break
            index[span.path.parent!!.name]?.remove(span) // out of the index first: no new reader can pin it
            freed += span.len
            victims += span.path
        }
        totalBytes -= freed
        return victims
    }

    // --- writer --------------------------------------------------------------------------------------

    private inner class RealBlobWriter(
        private val key: String,
        position: Long,
        private val token: String,
    ) : BlobWriter {
        private val dir = root / key
        private var blockStart = position
        private var blockBytes = 0L
        private var tmp: Path? = null
        private var tmpSink: BufferedSink? = null
        private var released = false

        override val sink: BufferedSink = RelaySink().buffer()

        /** Spills the region to disk block-by-block: each `write` fills the current temp until it reaches
         *  [maxSpanBytes], then seals it as an immutable span and starts the next. */
        private inner class RelaySink : Sink {
            override fun write(source: Buffer, byteCount: Long) {
                var remaining = byteCount
                while (remaining > 0) {
                    val s = tmpSink ?: openBlock()
                    val chunk = minOf(maxSpanBytes - blockBytes, remaining)
                    s.write(source, chunk)
                    blockBytes += chunk
                    remaining -= chunk
                    if (blockBytes >= maxSpanBytes) commitBlock()
                }
            }
            override fun flush() { tmpSink?.flush() }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() { tmpSink?.flush() }
        }

        // Temp name is unique per (region, block): blockStart alone can collide between two overlapping
        // regions that both start at the same offset (e.g. a progressive and a SABR writer), so include the
        // region token too — space-free so it's a clean filename. parseSpanName ignores `.tmp` either way.
        private val tempTag = token.replace(' ', '_')

        private fun openBlock(): BufferedSink {
            fileSystem.createDirectories(dir)
            val t = dir / "${blockStart}__$tempTag.$TMP_EXT"
            tmp = t
            return fileSystem.sink(t).buffer().also { tmpSink = it }
        }

        /** Seal the current temp into `{start}_{len}.span` and register it. No-op on an empty block. */
        private fun commitBlock() {
            val t = tmp ?: return
            tmpSink?.close(); tmpSink = null; tmp = null
            val len = blockBytes; blockBytes = 0
            val start = blockStart; blockStart += len
            if (len == 0L) { fileSystem.delete(t, mustExist = false); return }
            val spanPath = dir / spanName(start, len)
            if (fileSystem.exists(spanPath)) {
                // Identical range already committed (resume, or a concurrent equal writer beat us). Bytes are
                // identical by the contiguity invariant — drop our temp; don't atomicMove over a file a
                // reader may hold open. Make sure it's indexed, without double-counting its bytes.
                fileSystem.delete(t, mustExist = false)
                synchronized(lock) {
                    val list = index.getOrPut(key) { mutableListOf() }
                    if (list.none { it.start == start && it.len == len }) {
                        list += Span(start, len, spanPath, ++clock, pins = 0); totalBytes += len
                    }
                }
                return
            }
            fileSystem.atomicMove(t, spanPath)
            val victims = synchronized(lock) {
                index.getOrPut(key) { mutableListOf() } += Span(start, len, spanPath, ++clock, pins = 0)
                loadedKeys += key
                totalBytes += len
                planEviction(0)
            }
            victims.forEach { fileSystem.delete(it, mustExist = false) }
        }

        override fun commit() = finish(commitTail = true)
        override fun abort() = finish(commitTail = false)

        private fun finish(commitTail: Boolean) {
            if (released) return
            released = true
            if (commitTail) {
                sink.flush() // push whatever the buffered façade still holds into RelaySink → the temp
                commitBlock()
            } else {
                tmpSink?.close()
                tmp?.let { fileSystem.delete(it, mustExist = false) }
                tmpSink = null; tmp = null
            }
            val victims = synchronized(lock) { inFlight -= token; planEviction(0) }
            victims.forEach { fileSystem.delete(it, mustExist = false) }
        }
    }

    /** Reads pre-planned, pinned [chunks] in order; [close] unpins (idempotent). Inner so it shares [lock]. */
    private inner class StitchedSource(private val chunks: List<Chunk>) : PinnedSource {
        private var idx = 0
        private var current: BufferedSource? = null
        private var currentRemaining = 0L
        private var closed = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            check(!closed) { "closed" }
            while (true) {
                val src = current
                if (src != null && currentRemaining > 0) {
                    val n = src.read(sink, minOf(byteCount, currentRemaining))
                    if (n == -1L) { currentRemaining = 0; continue }
                    currentRemaining -= n
                    return n
                }
                current?.close(); current = null
                if (idx >= chunks.size) return -1
                val chunk = chunks[idx++]
                val s = fileSystem.source(chunk.path).buffer()
                if (chunk.skip > 0) s.skip(chunk.skip)
                current = s
                currentRemaining = chunk.take
            }
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            if (closed) return
            closed = true
            current?.close(); current = null
            synchronized(lock) { chunks.forEach { if (it.span.pins > 0) it.span.pins-- } }
        }
    }

    private fun flightToken(key: String, position: Long, length: Long) = "$key $position $length"

    private companion object {
        const val DEFAULT_BUDGET_BYTES = 512L * 1024 * 1024
        const val DEFAULT_MAX_SPAN_BYTES = 512L * 1024
        const val SPAN_EXT = "span"
        const val TMP_EXT = "tmp"

        fun spanName(start: Long, len: Long) = "${start}_$len.$SPAN_EXT"

        fun parseSpanName(name: String): Pair<Long, Long>? {
            if (!name.endsWith(".$SPAN_EXT")) return null
            val stem = name.removeSuffix(".$SPAN_EXT")
            val u = stem.indexOf('_')
            if (u <= 0) return null
            val start = stem.substring(0, u).toLongOrNull() ?: return null
            val len = stem.substring(u + 1).toLongOrNull() ?: return null
            return start to len
        }
    }
}

/** Discards its bytes — returned when the region is already committed or being written elsewhere. */
private class NoOpBlobWriter : BlobWriter {
    override val sink: BufferedSink = okio.blackholeSink().buffer()
    override fun commit() { /* nothing written */ }
    override fun abort() { /* nothing written */ }
}
