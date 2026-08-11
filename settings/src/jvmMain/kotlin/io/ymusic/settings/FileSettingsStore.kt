package io.ymusic.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.buffer
import java.util.Properties

/** [SettingsStore] persisted as a java.util.Properties file at [path]. */
class FileSettingsStore private constructor(
    private val fileSystem: FileSystem,
    private val path: Path,
) : StringBackedSettingsStore() {
    private val writeMutex = Mutex()

    override suspend fun persist() {
        writeMutex.withLock {
            // Snapshot inside the lock so a slow earlier writer can never overwrite
            // a later writer's key with a stale file image.
            val values = snapshot()
            withContext(Dispatchers.IO) {
                path.parent?.let(fileSystem::createDirectories)
                fileSystem.sink(path).buffer().use { sink ->
                    val props = Properties()
                    values.forEach { (k, v) -> props.setProperty(k, v) }
                    props.store(sink.outputStream(), null)
                }
            }
        }
    }

    companion object {
        suspend fun open(
            path: Path,
            fileSystem: FileSystem = FileSystem.SYSTEM,
        ): FileSettingsStore =
            withContext(Dispatchers.IO) {
                FileSettingsStore(fileSystem, path).apply {
                    if (fileSystem.exists(path)) {
                        val props = Properties()
                        fileSystem.source(path).buffer().use { props.load(it.inputStream()) }
                        seed(props.entries.associate { (k, v) -> k.toString() to v.toString() })
                    }
                }
            }
    }
}
