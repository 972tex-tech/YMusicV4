@file:OptIn(ExperimentalTime::class)

package kmphttp.cache

import kmpcommon.log.Logger
import kmpcommon.nowMs
import kmpcommon.removeAll
import kmphttp.internal.DefaultIODispatcher
import kmphttp.internal.ForwardingOkioSink
import kmphttp.internal.closeQuietly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Sink
import okio.Source
import okio.use
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

private class CacheState(
    initialMaxSize: Long,
    private val valueCount: Int
) {
    private val mutex = Mutex()

    private val lruEntries = LinkedHashMap<String, FileCacheEntry>()
    private var size: Long = 0
    private var journalWriter: JournalWriter? = null
    private var journalOpCount: Int = 0
    private var hasJournalErrors: Boolean = false

    /** Attached by the owning client; a journal fault disables persistence for the process life,
     *  so the FIRST one is worth a warning — later ones are the same incident. */
    internal var logger: Logger? = null
    private var initialized: Boolean = false
    private var closed = false
    private var mostRecentTrimFailed: Boolean = false
    private var mostRecentRebuildFailed: Boolean = false
    private var maxSize: Long = initialMaxSize
    private var editorIdInc: Int = 0

    // Non-blocking reads for simple properties
    fun getMaxSize(): Long = maxSize
    fun getSize(): Long = size
    fun isInitialized(): Boolean = initialized
    fun isClosed(): Boolean = closed

    /** Execute a block with exclusive access to mutable state */
    suspend fun <T> withState(block: suspend StateAccessor.() -> T): T = mutex.withLock {
        StateAccessor().block()
    }

    /** Provides controlled access to mutable state - only usable within withState block */
    inner class StateAccessor() {
        fun journalError(e: IOException) {
            if (!hasJournalErrors) {
                logger?.log(Logger.Level.WARN, "cache journal write failed — cache persistence disabled until restart", e)
            }
            hasJournalErrors = true
            setJournalWriter(null)
        }

        fun getMaxSize(): Long = maxSize
        fun setMaxSize(value: Long) {
            maxSize = value
        }

        fun getSize(): Long = size

        fun isInitialized(): Boolean = initialized
        fun init(journalFileState: JournalFileState) {
            check(!initialized)
            lruEntries.putAll(journalFileState.entries)
            size = journalFileState.entries.values.sumOf { it.cleanFiles.sumOf { it.second } }
            journalOpCount = journalFileState.opCount
            initialized = true
        }

        suspend fun closeCache() {
            if (isClosed()) return
            closed = true
            flushJournalFile()
            setJournalWriter(null)
            lruEntries.clear()
            size = 0
        }

        fun isClosed(): Boolean = closed

        fun hasJournalErrors(): Boolean = hasJournalErrors

        fun getMostRecentTrimFailed(): Boolean = mostRecentTrimFailed
        fun setMostRecentTrimFailed(value: Boolean) {
            mostRecentTrimFailed = value
        }

        fun getMostRecentRebuildFailed(): Boolean = mostRecentRebuildFailed
        fun setMostRecentRebuildFailed(value: Boolean) {
            mostRecentRebuildFailed = value
        }

        fun getJournalOpCount(): Int = journalOpCount
        fun setJournalOpCount(value: Int) {
            journalOpCount = value
        }

        fun hasJournalWriter(): Boolean = journalWriter != null
        fun setJournalWriter(writer: Sink?) {
            check(writer == null || !closed) { "Cache closed" }
            journalWriter?.sink?.closeQuietly()
            journalWriter = writer?.let { JournalWriter(it) }
            if (writer != null) hasJournalErrors = false
        }

        // LRU entries access
        fun getEntry(key: String): FileCacheEntry? = lruEntries[key]

        fun getOldestEntry(): FileCacheEntry? = lruEntries.entries.firstOrNull()?.value

        fun entriesIterator() = lruEntries.values.iterator()

        fun entriesCount(): Int = lruEntries.size

        fun genEditorId() = ++editorIdInc

        /** Move entry to end of LRU (most recently used) */
        suspend fun touchEntry(key: String): FileCacheEntry? {
            val entry = lruEntries.remove(key)
            if (entry != null) {
                lruEntries[key] = entry

                try {
                    journalWriter?.writeRead(key)
                    journalOpCount++
                } catch (e: IOException) {
                    journalError(e)
                }
            }
            return entry
        }

        private fun checkOpen() {
            check(initialized) { "!initialized" }
            check(!closed) { "cache closed" }
        }

        suspend fun commitEntry(entry: FileCacheEntry) {
            checkOpen()
            check(entry.cleanFiles.size == valueCount)

            val old = lruEntries.remove(entry.key)
            if (old != null) check(old.latestCommitEditorId < entry.latestCommitEditorId)
            lruEntries.put(entry.key, entry)

            val oldSize = old?.cleanFiles?.sumOf { it.second } ?: 0L
            val newSize = entry.cleanFiles.sumOf { it.second }
            size += (newSize - oldSize)

            try {
                journalWriter?.writeCommit(entry)
                journalOpCount++
            } catch (e: IOException) {
                journalError(e)
            }
        }

        suspend fun removeEntry(key: String): FileCacheEntry? {
            checkOpen()
            val entry = lruEntries.remove(key)
            if (entry != null) {
                size -= entry.cleanFiles.sumOf { it.second }
                try {
                    journalWriter?.writeRemove(entry.key)
                    journalOpCount++
                } catch (e: IOException) {
                    journalError(e)
                }
            }
            return entry
        }

        suspend fun flushJournalFile() {
            journalWriter?.sink?.flush()
        }
    }
}

/**
 * Disk LRU cache, modified from OkHttp's DiskLruCache.
 * Note that it's not compatible with OkHttp's disk LRU cache implementation:
 * - Journal file structures are different
 * - Files (clean and dirty) are uniquely named, independent from cache key.
 * - Concurrent edit or delete a key: the editor started later will win.
 */
@OptIn(ExperimentalAtomicApi::class)
class DiskLruCache(
    private val fileSystem: FileSystem,
    private val appVersion: Int = 1,
    val directory: Path,
    val valueCount: Int = 2,
    maxSize: Long
) {

    // Thread-safe state container
    private val state = CacheState(maxSize, valueCount)

    /** Diagnostics sink, attached by the owning client (journal faults warn once). */
    internal var logger: Logger?
        get() = state.logger
        set(value) {
            state.logger = value
        }

    private val tempDir = directory / TEMP_DIR

    // Journal file paths
    private val journalFile: Path = directory / JOURNAL_FILE
    private val journalFileTmp: Path = directory / JOURNAL_FILE_TEMP
    private val journalFileBackup: Path = directory / JOURNAL_FILE_BACKUP

    // Background cleanup scope
    private val cleanupScope = CoroutineScope(SupervisorJob() + DefaultIODispatcher)

    val maxSize: Long get() = state.getMaxSize()
    val currentSize: Long get() = state.getSize()
    val initialized: Boolean get() = state.isInitialized()
    val closed: Boolean get() = state.isClosed()

    suspend fun setMaxSize(value: Long) {
        state.withState {
            setMaxSize(value)
            if (isInitialized() && !isClosed()) {
                cleanupInState()
            }
        }
    }

    /**
     * Initialize the cache. Reads the journal if it exists, otherwise creates a new one.
     */
    suspend fun initialize() {
        if (initialized || closed) return
        state.withState {
            initializeInState()
        }
    }

    suspend fun flush() {
        state.withState {
            flushJournalFile()
        }
    }

    suspend fun trimNow() {
        state.withState {
            trimToSizeInState()
        }
    }

    suspend fun close() {
        state.withState {
            closeCache()
        }
        cleanupScope.cancel()
    }

    private suspend fun CacheState.StateAccessor.initializeInState() {
        if (isInitialized() || isClosed()) return

        fileSystem.createDirectories(directory)
        try {
            fileSystem.deleteRecursively(tempDir)
        } catch (e: IOException) {
            logger?.log(Logger.Level.WARN, "cache temp dir cleanup failed", e)
        }
        fileSystem.createDirectories(tempDir)
        if (fileSystem.metadataOrNull(tempDir)?.isDirectory != true) {
            throw IOException("Can't create $tempDir directory")
        }

        // If a backup file exists, use it instead
        if (fileSystem.exists(journalFileBackup)) {
            if (fileSystem.exists(journalFile)) {
                fileSystem.deleteRecursively(journalFileBackup)
            } else {
                fileSystem.atomicMove(journalFileBackup, journalFile)
            }
        }

        val journalFileState = if (fileSystem.exists(journalFile)) {
            try {
                fileSystem.source(journalFile).use { source ->
                    source.readJournalFile(appVersion, valueCount)
                }
            } catch (e: IOException) {
                logger?.log(Logger.Level.WARN, "cache journal unreadable — starting empty", e)
                fileSystem.deleteRecursively(journalFile)
                JournalFileState(appVersion, valueCount, 0, false, linkedMapOf())
            }
        } else {
            JournalFileState(appVersion, valueCount, 0, false, linkedMapOf())
        }
        val entryFileNames = HashSet<String>(journalFileState.entries.size * valueCount)
        journalFileState.entries.forEach {
            it.value.cleanFiles.forEach { (fileName, _) ->
                entryFileNames.add(fileName)
            }
        }

        val directoryFileNames = fileSystem.list(directory)
            .mapTo(mutableSetOf()) { it.name }
        directoryFileNames.remove(JOURNAL_FILE)
        directoryFileNames.remove(TEMP_DIR)

        // delete file that not exists in journal
        (directoryFileNames - entryFileNames).forEach { fileName ->
            fileSystem.deleteRecursively(directory / fileName)
        }

        // filter entry that has files no longer exists in directory
        journalFileState.entries.removeAll { entry ->
            entry.value.cleanFiles.any { (fileName, _) -> fileName !in directoryFileNames }
        }

        if (!journalFileState.isComplete) {
            rebuildJournalInState()
        } else {
            setJournalWriter(fileSystem.appendingSink(journalFile))
        }

        init(journalFileState)
    }

    private suspend fun CacheState.StateAccessor.cleanupInState() {
        if (!isInitialized() || isClosed()) return

        try {
            trimToSizeInState()
        } catch (e: IOException) {
            logger?.log(Logger.Level.WARN, "cache trim failed", e)
            setMostRecentTrimFailed(true)
        }

        try {
            if (journalRebuildRequiredInState()) {
                rebuildJournalInState()
            }
        } catch (e: IOException) {
            logger?.log(Logger.Level.WARN, "cache journal rebuild failed", e)
            setMostRecentRebuildFailed(true)
            setJournalWriter(null)
        }
    }

    private suspend fun CacheState.StateAccessor.trimToSizeInState() {
        if (isClosed()) return
        initializeInState()
        while (getSize() > getMaxSize()) {
            if (!removeOldestEntryInState()) return
        }
        setMostRecentTrimFailed(false)
    }

    private suspend fun CacheState.StateAccessor.removeOldestEntryInState(): Boolean {
        val oldest = getOldestEntry() ?: return false
        return removeEntryInState(oldest)
    }

    private suspend fun CacheState.StateAccessor.removeEntryInState(entry: FileCacheEntry): Boolean {
        if (!isInitialized() || isClosed()) return false
        // only remove if this entry is really in the cache
        if (getEntry(entry.key) !== entry) return false

        val removed = removeEntry(entry.key) ?: return false
        removed.cleanFiles.forEach { (fileName) ->
            val filePath = directory / fileName
            fileSystem.deleteRecursively(filePath)
        }

        if (journalRebuildRequiredInState()) {
            scheduleCleanup()
        }

        return true
    }

    private fun CacheState.StateAccessor.journalRebuildRequiredInState(): Boolean {
        val redundantOpCompactThreshold = 2000
        return getJournalOpCount() >= entriesCount() + redundantOpCompactThreshold
    }

    private val cleanUpScheduled = AtomicBoolean(false)
    private var lastCleanedUpAt = 0L

    @OptIn(ExperimentalTime::class)
    private fun scheduleCleanup() {
        if (cleanUpScheduled.compareAndSet(false, true)) {
            cleanupScope.launch {
                val cleanUpThrottleMs = 60000
                val sleepMs = (lastCleanedUpAt + cleanUpThrottleMs) - Clock.System.nowMs()
                if (sleepMs > 0) {
                    delay(sleepMs)
                }
                lastCleanedUpAt = Clock.System.nowMs()

                cleanUpScheduled.store(false)
                state.withState {
                    cleanupInState()
                }
            }
        }
    }

    private suspend fun CacheState.StateAccessor.rebuildJournalInState() {
        // close current writer
        setJournalWriter(null)

        fileSystem.sink(journalFileTmp).use { sink ->
            val journalWriter = JournalWriter(sink)
            journalWriter.writeHeader(appVersion, valueCount)

            entriesIterator().forEach {
                journalWriter.writeCommit(it)
            }
        }

        if (fileSystem.exists(journalFile)) {
            fileSystem.atomicMove(journalFile, journalFileBackup)
            fileSystem.atomicMove(journalFileTmp, journalFile)
            fileSystem.deleteRecursively(journalFileBackup)
        } else {
            fileSystem.atomicMove(journalFileTmp, journalFile)
        }

        setJournalWriter(fileSystem.appendingSink(journalFile))
        setJournalOpCount(entriesCount())
        setMostRecentRebuildFailed(false)
    }

    /**
     * Returns a snapshot of the entry named [key], or null if it doesn't exist or is not currently
     * readable. If a value is returned, it is moved to the head of the LRU queue.
     */
    suspend fun get(key: String): Snapshot? {
        val entry = state.withState {
            initializeInState()
            if (isClosed()) return@withState null
            val entry = getEntry(key) ?: return@withState null

            // Update LRU order
            touchEntry(key)

            if (journalRebuildRequiredInState()) {
                scheduleCleanup()
            }

            entry
        }
        if (entry != null) {
            val snapshot = createSnapshot(entry)
            if (snapshot == null) {
                // something wrong with this entry, remove it
                state.withState {
                    removeEntryInState(entry)
                }
            }
            return snapshot
        }
        return null
    }

    suspend fun remove(key: String): Boolean {
        return state.withState {
            initializeInState()
            if (isClosed()) return@withState false
            getEntry(key)?.let { removeEntryInState(it) } == true
        }
    }

    suspend fun edit(key: String): Editor? {
        validateKey(key)
        return state.withState {
            editInState(key)
        }
    }

    private suspend fun CacheState.StateAccessor.editInState(key: String): Editor? {
        initializeInState()
        if (isClosed() || hasJournalErrors() || !hasJournalWriter()) {
            return null
        }

        if (getMostRecentTrimFailed() || getMostRecentRebuildFailed()) {
            scheduleCleanup()
            return null
        }

        val existingEntry = getEntry(key)
        return Editor(key, genEditorId(), existingEntry)
    }

    private fun validateKey(key: String) {
        require(LEGAL_KEY_PATTERN.matches(key)) {
            "keys must match regex ${LEGAL_KEY_PATTERN}: \"$key\""
        }
    }

    /**
     * @return true if [success] and state was committed on disk.
     */
    private suspend fun CacheState.StateAccessor.commitEditInState(
        editor: Editor,
        success: Boolean
    ): Boolean {
        if (!success || isClosed()) {
            editor.zombie = true
            editor.dirtyFiles.forEach {
                if (it != null) fileSystem.deleteRecursively(it)
            }
            return false
        } else {
            if (editor.dirtyFiles.all { it == null }) {
                // nothing changed
                return true
            }

            val currentEntryForKey = getEntry(editor.key)
            if (currentEntryForKey != null && currentEntryForKey.latestCommitEditorId > editor.id) {
                // another editor with higher id commited, so abort this
                return commitEditInState(editor, success = false)
            }
            if (currentEntryForKey == null && editor.existingEntry != null) {
                // key was deleted in the meantime
                return commitEditInState(editor, success = false)
            }

            if (editor.existingEntry == null) {
                repeat(valueCount) { i ->
                    require(editor.dirtyFiles[i] != null) {
                        "You must provide value for index $i"
                    }
                }
            } else if (editor.existingEntry !== currentEntryForKey) {
                // previous entry state was changed
                // only commit if the editor override all the values
                if (editor.dirtyFiles.any { it == null }) {
                    return commitEditInState(editor, success = false)
                }
            }

            val stagingFiles = mutableListOf<Path>()
            val newCleanFiles = try {
                List(valueCount) { i ->
                    val dirtyFile = editor.dirtyFiles[i]
                    if (dirtyFile != null) {
                        val newFileName = newUniqueFileName() + ".data"
                        val newFilePath = directory / newFileName
                        stagingFiles += newFilePath

                        check(!fileSystem.exists(newFilePath))
                        fileSystem.atomicMove(dirtyFile, newFilePath)

                        val fileSize = checkNotNull(fileSystem.metadataOrNull(newFilePath)?.size)
                        newFileName to fileSize
                    } else {
                        checkNotNull(editor.existingEntry).cleanFiles[i]
                    }
                }
            } catch (e: Throwable) {
                logger?.log(Logger.Level.WARN, "cache commit failed — edit discarded", e)
                stagingFiles.forEach { fileSystem.deleteRecursively(it) }
                return commitEditInState(editor, success = false)
            }
            val newEntry = FileCacheEntry(editor.key, newCleanFiles, editor.id)
            commitEntry(newEntry)

            // Trim to size
            if (getSize() > getMaxSize()) {
                scheduleCleanup()
            }

            return true
        }
    }

    private suspend fun createSnapshot(entry: FileCacheEntry): Snapshot? {
        val sources = mutableListOf<Source>()
        try {
            for (i in 0 until valueCount) {
                val path = directory / entry.cleanFiles[i].first
                sources += fileSystem.source(path)
            }
            return Snapshot(entry, sources)
        } catch (e: IOException) {
            logger?.log(Logger.Level.WARN, "cache entry files unreadable — treated as a miss", e)
            for (source in sources) {
                source.closeQuietly()
            }
            return null
        } catch (e: Throwable) {
            for (source in sources) {
                source.closeQuietly()
            }
            throw e
        }
    }

    inner class Snapshot internal constructor(
        private val entry: FileCacheEntry,
        private val sources: List<Source>
    ) : AutoCloseable {

        fun key(): String = entry.key

        fun getSource(index: Int) = sources[index]
        fun getLength(index: Int): Long = entry.cleanFiles[index].second

        /** The entry's backing file — immutable once committed (files are unique-named, never
         *  rewritten in place); an eviction unlinks it, which a later open turns into a miss. */
        fun getPath(index: Int): Path = directory / entry.cleanFiles[index].first

        suspend fun edit(): Editor? {
            return state.withState {
                if (getEntry(key()) === entry) {
                    editInState(key())
                } else null
            }
        }

        override fun close() {
            for (source in sources) {
                source.closeQuietly()
            }
        }
    }

    inner class Editor internal constructor(
        val key: String,
        val id: Int,
        internal val existingEntry: FileCacheEntry?
    ) {
        internal var zombie = false
        internal val dirtyFiles = MutableList<Path?>(valueCount) { null }

        private var done = false

        fun newSink(index: Int): Sink {
            check(!done)
            val dirtyFile = dirtyFiles[index] ?: (tempDir / (newUniqueFileName() + ".tmp")).also {
                dirtyFiles[index] = it
            }
            // The OS may wipe the cache dir while we run (Android, low storage).
            fileSystem.createDirectories(tempDir)
            val delegate = fileSystem.sink(dirtyFile)
            return object : ForwardingOkioSink(delegate) {
                override fun close() {
                    super.close()
                    if (zombie) {
                        fileSystem.deleteRecursively(dirtyFile)
                    }
                }
            }
        }

        suspend fun commit(): Boolean {
            return state.withState {
                check(!done)
                done = true
                commitEditInState(this@Editor, success = true)
            }
        }

        suspend fun abort(): Boolean {
            return state.withState {
                check(!done)
                done = true
                commitEditInState(this@Editor, success = false)
            }
        }
    }

    companion object {
        const val TEMP_DIR = "temp"
        const val JOURNAL_FILE = "journal"
        const val JOURNAL_FILE_TEMP = "journal.tmp"
        const val JOURNAL_FILE_BACKUP = "journal.bkp"
        val LEGAL_KEY_PATTERN = "[a-z0-9_-]{1,120}".toRegex()

        // Jan 1, 2026 00:00:00 UTC in milliseconds
        private const val EPOCH_2026 = 1767225600000L
        private val timeStart = Clock.System.now().toEpochMilliseconds() - EPOCH_2026
        private val timeMark = TimeSource.Monotonic.markNow()
        private val fileCounter = AtomicInt(0)
        private val random = Random(Random.nextLong())

        /**
         * Generates short unique file names: timestamp + counter + random.
         */
        @OptIn(ExperimentalTime::class, ExperimentalAtomicApi::class)
        private fun newUniqueFileName(): String {
            val ts = (timeStart + timeMark.elapsedNow().inWholeMilliseconds).toString(16)
            val cnt = (fileCounter.incrementAndFetch() and 0xFFFF).toString(16)
            val ran = (random.nextInt() and 0xFFFF).toString(16)
            return "$ts$cnt$ran"
        }
    }
}
