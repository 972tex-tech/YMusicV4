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
 * `{root}/{key}/{start}_{len}.span`; a persistent [BlobIndex] (SQLite via [SqlDelightBlobIndex]) is the
 * index of record — which ranges are cached, the total cached size, and LRU recency — so coverage, the
 * byte budget and eviction order never depend on scanning directories and all survive a restart. Read
 * pins are the one piece of state that stays in memory (they are per-process: a span being
 * read right now must not be evicted). The index (and pins / in-flight) are guarded by one
 * [lock] — small critical sections around point/range queries; the large byte IO (spilling and reading
 * span files) happens outside it.
 *
 * This reproduces media3 `SimpleCache`'s six concurrency guarantees without media3 (see
 * `docs/player/sabr-cache-plan.md` §3a):
 *
 * 1. **Atomic commit / immutable spans** — a block streams to a `.tmp`, then `atomicMove`s to its
 *    `{start}_{len}.span` and only then gets a row. Readers open committed `.span` files, so a partial
 *    write is never visible.
 * 2. **Per-block commit** — a region is cut into blocks of ≤[maxSpanBytes]; each commits independently, so
 *    an interrupted write keeps the blocks it finished (valid bytes for their ranges) and a retry resumes.
 * 3. **Single-writer-per-region dedupe** — an in-flight set keyed `(key, start, len)`; [writer] hands back
 *    a no-op sink when the exact region is already committed (a row exists) or in flight.
 * 4. **Read pinning** — [read] ref-count-pins its covering spans; eviction never touches a pinned span.
 * 5. **Plan-then-reacquire** — [isCached] is a hint; [read] re-checks coverage **and that the files still
 *    exist** under the lock, returning null (→ network) on any gap (a stale row whose file is gone is
 *    dropped), holding no lock across plan→serve.
 * 6. **LRU eviction** — on commit, delete least-recently-used **unpinned** spans (rows + files) until
 *    `SUM(len) ≤ [budgetBytes]`.
 */
@WorkerThread
class DiskMediaBlobCache(
    private val root: Path,
    private val fileSystem: FileSystem,
    private val index: BlobIndex,
    private val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    private val maxSpanBytes: Long = DEFAULT_MAX_SPAN_BYTES,
) : MediaBlobCache {

    /** One contiguous slice to read from one span: `[skip, skip+take)` of the span `(key,start,len)`. */
    private class Chunk(val key: String, val start: Long, val len: Long, val skip: Long, val take: Long)

    private val lock = SynchronizedObject()
    private val pins = mutableMapOf<String, Int>() // spanId → read-pin count (in-memory, per-process)
    private val inFlight = mutableSetOf<String>()
    private val sweptKeys = mutableSetOf<String>() // key dirs whose stale temps we've swept this process

    override fun isCached(key: String, position: Long, length: Long): Boolean = synchronized(lock) {
        cover(key, index.spansForKey(key), position, length) != null
    }

    override fun read(key: String, position: Long, length: Long): PinnedSource? {
        val chunks = synchronized(lock) {
            val plan = cover(key, index.spansForKey(key), position, length) ?: return@synchronized null
            // The DB is a hint: a row can outlive its file (crash between delete-row/delete-file elsewhere,
            // or external deletion). Verify every covering file exists; a missing one drops its stale row
            // and collapses the read to a miss (plan-then-reacquire).
            for (c in plan) {
                if (!fileSystem.exists(pathOf(c.key, c.start, c.len))) {
                    index.delete(c.key, c.start, c.len)
                    return@synchronized null
                }
            }
            plan.forEach {
                pin(spanId(it.key, it.start, it.len))
                index.touch(it.key, it.start, it.len) // LRU touch (stamps now), persisted
            }
            plan
        } ?: return null
        return StitchedSource(chunks)
    }

    override fun writer(key: String, position: Long, length: Long): BlobWriter {
        val token = flightToken(key, position, length)
        val real = synchronized(lock) {
            val taken = cover(key, index.spansForKey(key), position, length) != null || token in inFlight
            if (!taken) inFlight += token
            !taken
        }
        if (!real) return NoOpBlobWriter()
        sweepStaleTemps(key)
        return RealBlobWriter(key, position, token)
    }

    override fun keys(): Set<String> = synchronized(lock) { index.distinctKeys() }

    override fun totalSize(): Long = synchronized(lock) { index.totalSize() }

    private fun pin(id: String) { pins[id] = (pins[id] ?: 0) + 1 }

    private fun unpin(id: String) {
        val n = pins[id] ?: return
        if (n <= 1) pins.remove(id) else pins[id] = n - 1
    }

    // --- covering (greedy max-reach; tolerates overlapping spans) ------------------------------------

    /**
     * Chunks exactly covering `[position, position+length)`, or null on any gap. **[spans] must be sorted
     * by `start` ascending** ([BlobIndex.spansForKey] guarantees it). One O(n) greedy sweep: at each cursor
     * take the reachable span (`start ≤ cursor`) with the furthest `end`, advancing a single pointer [i]
     * through [spans] that never rewinds — a span left behind ends at or before the cursor, so it can never
     * help a later cursor. Overlap is tolerated (bytes are identical by the contiguity invariant). Call
     * under [lock].
     */
    private fun cover(key: String, spans: List<BlobSpan>, position: Long, length: Long): List<Chunk>? {
        if (length <= 0) return emptyList()
        if (spans.isEmpty()) return null
        val end = position + length
        var cursor = position
        var i = 0
        val chunks = ArrayList<Chunk>()
        while (cursor < end) {
            // Among spans starting at or before the cursor, the one reaching furthest past it. `bestEnd`
            // starts at `cursor`, so only spans that actually cover it (end > cursor) can win.
            var best: BlobSpan? = null
            var bestEnd = cursor
            while (i < spans.size && spans[i].start <= cursor) {
                val s = spans[i]
                if (s.end > bestEnd) { bestEnd = s.end; best = s }
                i++
            }
            val b = best ?: return null // gap: nothing covers the cursor
            val take = minOf(bestEnd - cursor, end - cursor)
            chunks += Chunk(key, b.start, b.len, skip = cursor - b.start, take = take)
            cursor += take
        }
        return chunks
    }

    // --- eviction ------------------------------------------------------------------------------------

    /** Under [lock], delete unpinned LRU spans (rows now) until `SUM(len)+incoming ≤ budget`; returns their
     *  files to delete outside the lock. */
    private fun planEviction(incoming: Long): List<Path> {
        var total = index.totalSize()
        if (total + incoming <= budgetBytes) return emptyList()
        val victims = ArrayList<Path>()
        for (row in index.lruOrder()) {
            if (total + incoming <= budgetBytes) break
            if ((pins[spanId(row.key, row.start, row.len)] ?: 0) > 0) continue // pinned → never evict
            index.delete(row.key, row.start, row.len)
            total -= row.len
            victims += pathOf(row.key, row.start, row.len)
        }
        return victims
    }

    /** Sweep a key dir's leftover `.tmp` files from a crashed writer, once per key per process — but NEVER
     *  one a live in-flight writer for this key is mid-write on (its token is in [inFlight], and a temp is
     *  created only after its token is registered, so this snapshot covers every live temp; an already
     *  committed temp just no-op-deletes). */
    private fun sweepStaleTemps(key: String) {
        if (synchronized(lock) { !sweptKeys.add(key) }) return
        val dir = root / key
        if (!fileSystem.exists(dir)) return
        val temps = fileSystem.list(dir).filter { it.name.endsWith(".$TMP_EXT") }
        if (temps.isEmpty()) return
        val liveTags = synchronized(lock) { inFlight.mapTo(HashSet()) { it.replace(' ', '_') } }
        for (t in temps) {
            val tag = t.name.substringAfter("__", "").removeSuffix(".$TMP_EXT")
            if (tag !in liveTags) fileSystem.delete(t, mustExist = false)
        }
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
        // region token too — space-free so it's a clean filename.
        private val tempTag = token.replace(' ', '_')

        private fun openBlock(): BufferedSink {
            fileSystem.createDirectories(dir)
            val t = dir / "${blockStart}__$tempTag.$TMP_EXT"
            tmp = t
            return fileSystem.sink(t).buffer().also { tmpSink = it }
        }

        /** Seal the current temp into `{start}_{len}.span` and index it. No-op on an empty block. */
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
                // reader may hold open. Make sure it's indexed (INSERT OR IGNORE).
                fileSystem.delete(t, mustExist = false)
                synchronized(lock) { index.insert(key, start, len) }
                return
            }
            fileSystem.atomicMove(t, spanPath)
            val victims = synchronized(lock) {
                index.insert(key, start, len)
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
                val s = fileSystem.source(pathOf(chunk.key, chunk.start, chunk.len)).buffer()
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
            synchronized(lock) { chunks.forEach { unpin(spanId(it.key, it.start, it.len)) } }
        }
    }

    private fun pathOf(key: String, start: Long, len: Long): Path = root / key / spanName(start, len)

    private fun spanId(key: String, start: Long, len: Long) = "$key $start $len"

    private fun flightToken(key: String, position: Long, length: Long) = "$key $position $length"

    private companion object {
        const val DEFAULT_BUDGET_BYTES = 512L * 1024 * 1024
        const val DEFAULT_MAX_SPAN_BYTES = 512L * 1024
        const val SPAN_EXT = "span"
        const val TMP_EXT = "tmp"

        fun spanName(start: Long, len: Long) = "${start}_$len.$SPAN_EXT"
    }
}

/** Discards its bytes — returned when the region is already committed or being written elsewhere. */
private class NoOpBlobWriter : BlobWriter {
    override val sink: BufferedSink = okio.blackholeSink().buffer()
    override fun commit() { /* nothing written */ }
    override fun abort() { /* nothing written */ }
}
