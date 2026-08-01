package ymusic.ytmedia.cache

import kmpcommon.WorkerThread
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/**
 * Append-only access journal for [DiskMediaBlobCache], modeled on OkHttp's `DiskLruCache` journal.
 * The immutable `{start}_{len}.span` files carry no access time (and no platform's atime is dependable),
 * so LRU recency would reset to directory-enumeration order on every restart. Instead every read /
 * commit appends the touched span's id here, and on open the log is [replay]ed to recover the order
 * spans were last used. When it grows past [compactThreshold] appends it is [rewrite]n from the live
 * set (oldest→newest), so its size stays bounded by the number of live spans.
 *
 * One line per touch — just the span id `"{key} {start} {len}"` (`key` = `2_{itag}_{lmt}` never has a
 * space). Re-touching an id moves it to most-recent; the file is an event log, not a keyed store, so
 * the append is O(1) and needs no read-modify-write. Eviction writes nothing: a dropped span's stale
 * lines are simply ignored on replay (no matching file) and purged at the next compaction.
 */
@WorkerThread
internal class AccessJournal(
    private val root: Path,
    private val fileSystem: FileSystem,
    private val compactThreshold: Int = 4000,
) {
    private val file = root / FILE_NAME
    private val lock = SynchronizedObject()
    private var sink: BufferedSink? = null // held open for the cache's lifetime (like DiskLruCache)
    private var appended = 0

    /**
     * Replay the log → span ids in LRU order, **oldest first**. Later occurrences win (re-touch =
     * most-recent), so a `LinkedHashMap`'s insertion order after re-inserting each line is exactly the
     * recency order. A missing journal → empty; a truncated tail (crash mid-append) → whatever parsed.
     */
    fun replay(): List<String> = synchronized(lock) {
        sink?.flush() // in case this instance already appended (belt-and-suspenders; replay runs at open)
        if (!fileSystem.exists(file)) return emptyList()
        val order = LinkedHashMap<String, Unit>()
        runCatching {
            fileSystem.source(file).buffer().use { source ->
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isNotEmpty()) {
                        order.remove(line) // move to most-recent (re-insert at the end)
                        order[line] = Unit
                    }
                }
            }
        }
        order.keys.toList()
    }

    /** Append one touch on the held sink and flush it (a write syscall, no fsync) — so a fresh instance
     *  replaying without an explicit [close] still sees it. Losing the tail on a crash only costs some
     *  recency precision, never correctness. */
    fun record(id: String) = synchronized(lock) {
        val s = sink ?: openSink()
        s.writeUtf8(id).writeUtf8("\n").flush()
        appended++
    }

    private fun openSink(): BufferedSink {
        fileSystem.createDirectories(root)
        return fileSystem.appendingSink(file).buffer().also { sink = it }
    }

    fun needsCompaction(): Boolean = synchronized(lock) { appended > compactThreshold }

    /** Replace the log with one line per live id in [orderedOldestFirst] (atomic tmp+move). The held
     *  append sink is closed first — its fd points at the pre-rename inode, so it would write to a ghost
     *  file after the move; the next [record] reopens onto the fresh file. */
    fun rewrite(orderedOldestFirst: List<String>) = synchronized(lock) {
        sink?.close(); sink = null
        fileSystem.createDirectories(root)
        val tmp = root / "$FILE_NAME.$TMP_EXT"
        fileSystem.sink(tmp).buffer().use { out -> orderedOldestFirst.forEach { out.writeUtf8(it).writeUtf8("\n") } }
        fileSystem.atomicMove(tmp, file)
        appended = 0
    }

    /** Release the append fd. Optional — the cache is process-lifetime, so this is for tests / shutdown. */
    fun close() = synchronized(lock) { sink?.close(); sink = null }

    companion object {
        const val FILE_NAME = "access-journal"
        private const val TMP_EXT = "tmp"
    }
}
