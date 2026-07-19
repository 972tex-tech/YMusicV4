package kmphttp.cache

import okio.Buffer
import okio.EOFException
import okio.IOException
import okio.Sink
import okio.Source
import okio.buffer
import okio.use

internal class FileCacheEntry(
    val key: String,
    val cleanFiles: List<Pair<String, Long>>,
    val latestCommitEditorId: Int = 0
)

/**
 * Thread-safe mutable state container for DiskLruCache.
 * Journal File Structure
 *     kmphttp.cache.DiskLruCache
 *     1
 *     100
 *     2
 *
 *     CM 3400330d1dfc7f3f7f4b8d4d803dfcf6 58EFB3D2-BE33-41B1-A258-25845D8C91CB.data 21054 C0F65CD6-F830-4B4A-A4B2-348834837994.data 12334
 *     RD 3400330d1dfc7f3f7f4b8d4d803dfcf6
 *     RM 3400330d1dfc7f3f7f4b8d4d803dfcf6
 *
 * The first five lines of the journal form its header. They are the constant string
 * "kmphttp.cache.DiskLruCache", the disk cache's version, the application's version, the value
 * count, and a blank line.
 *
 * Each of the subsequent lines in the file is a record of the state of a cache entry. Each line
 * contains space-separated values: a state, a key, and optional state-specific values.
 *
 *   o CM (COMMIT) lines track that an entry is actively being added or updated. A
 *     publish line is followed by [file name, file size][valueCount].
 *
 *   o RD (READ) lines track accesses for LRU.
 *
 *   o RM (REMOVE) lines track entries that have been deleted.
 *
 * The journal file is appended to as cache operations occur. The journal may occasionally be
 * compacted by dropping redundant lines.
 *
 * The journal file which has not been "flush()-ed" completely still consider as valid,
 * in this case [isComplete] will be false, and the file should be rebuilt before append more data.
 */
internal class JournalFileState(
    val appVersion: Int,
    val valueCount: Int,
    val opCount: Int,
    val isComplete: Boolean,
    val entries: LinkedHashMap<String, FileCacheEntry>
) {
    companion object {
        const val OP_COMMIT = "CM"
        const val OP_READ = "RD"
        const val OP_REMOVE = "RM"

        const val MAGIC = "kmphttp.cache.DiskLruCache"
        const val VERSION_1 = "1"
    }
}

/**
 * Read a journal file and return its state.
 */
internal fun Source.readJournalFile(
    expectAppVersion: Int,
    expectValueCount: Int
): JournalFileState = buffer().use { buffer ->
    require(expectValueCount > 0)
    val magic = buffer.readUtf8LineStrict()
    val version = buffer.readUtf8LineStrict()
    val appVersionString = buffer.readUtf8LineStrict()
    val valueCountString = buffer.readUtf8LineStrict()
    val blank = buffer.readUtf8LineStrict()

    if (magic != JournalFileState.MAGIC ||
        version != JournalFileState.VERSION_1 ||
        appVersionString != expectAppVersion.toString() ||
        valueCountString != expectValueCount.toString() ||
        blank.isNotEmpty()
    ) {
        throw IOException(
            "unexpected journal header: [$magic, $version, $appVersionString, $valueCountString, $blank]"
        )
    }

    val entries = LinkedHashMap<String, FileCacheEntry>()
    var opCount = 0
    while (true) {
        try {
            val line = buffer.readUtf8LineStrict()
            val lineParts = line.split(' ')
            if (lineParts.size >= 2) run parsing@{
                val key = lineParts[1]
                when (lineParts[0]) {
                    JournalFileState.OP_COMMIT -> {
                        if (lineParts.size - 2 == expectValueCount * 2) {
                            entries[key] = FileCacheEntry(
                                key,
                                List(expectValueCount) { i ->
                                    val fileName = lineParts[i * 2 + 2]
                                    val fileSize = lineParts[i * 2 + 3].toLongOrNull()
                                        ?: return@parsing
                                    Pair(fileName, fileSize)
                                }
                            )
                            opCount++
                            continue
                        }
                    }

                    JournalFileState.OP_READ -> {
                        // remove and add it back to reorder it to the end of the linked map
                        entries.remove(key)?.let { entries.put(key, it) }
                        opCount++
                        continue
                    }

                    JournalFileState.OP_REMOVE -> {
                        entries.remove(key)
                        opCount++
                        continue
                    }
                }
            }

            throw IOException("unexpected journal line: $line")
        } catch (_: EOFException) {
            break
        }
    }
    return JournalFileState(
        appVersion = expectAppVersion,
        valueCount = expectValueCount,
        opCount = opCount,
        isComplete = buffer.exhausted(),
        entries = entries
    )
}

internal class JournalWriter(val sink: Sink) {
    fun writeCommit(entry: FileCacheEntry) {
        val buffer = Buffer()
        buffer.writeUtf8(JournalFileState.OP_COMMIT)
        buffer.writeByte(' '.code)
        buffer.writeUtf8(entry.key)
        buffer.writeByte(' '.code)
        entry.cleanFiles.forEachIndexed { index, (fileName, fileSize) ->
            buffer.writeUtf8(fileName)
            buffer.writeByte(' '.code)
            buffer.writeDecimalLong(fileSize)
            if (index < entry.cleanFiles.size - 1) {
                buffer.writeByte(' '.code)
            }
        }
        buffer.writeByte('\n'.code)

        sink.write(buffer, buffer.size)
        check(buffer.size == 0L)
        sink.flush()
    }

    fun writeRead(key: String) {
        val buffer = Buffer()
        buffer.writeUtf8(JournalFileState.OP_READ)
        buffer.writeByte(' '.code)
        buffer.writeUtf8(key)
        buffer.writeByte('\n'.code)
        sink.write(buffer, buffer.size)
        check(buffer.size == 0L)
        sink.flush()
    }

    fun writeRemove(key: String) {
        val buffer = Buffer()
        buffer.writeUtf8(JournalFileState.OP_REMOVE)
        buffer.writeByte(' '.code)
        buffer.writeUtf8(key)
        buffer.writeByte('\n'.code)
        sink.write(buffer, buffer.size)
        check(buffer.size == 0L)
        sink.flush()
    }

    fun writeHeader(appVersion: Int, valueCount: Int) {
        val buffer = Buffer()
        buffer.writeUtf8(JournalFileState.MAGIC).writeByte('\n'.code)
        buffer.writeUtf8(JournalFileState.VERSION_1).writeByte('\n'.code)
        buffer.writeDecimalLong(appVersion.toLong()).writeByte('\n'.code)
        buffer.writeDecimalLong(valueCount.toLong()).writeByte('\n'.code)
        buffer.writeByte('\n'.code)

        sink.write(buffer, buffer.size)
        check(buffer.size == 0L)
        sink.flush()
    }
}
