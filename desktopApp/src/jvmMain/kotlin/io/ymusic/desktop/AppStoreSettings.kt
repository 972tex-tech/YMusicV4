package io.ymusic.desktop

import io.ymusic.settings.StringBackedSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The desktop [io.ymusic.settings.SettingsStore], one [AppStore] row per key. */
internal class AppStoreSettings private constructor(private val store: AppStore) : StringBackedSettingsStore() {
    private val writeMutex = Mutex()

    /** What the rows hold, so a write commits only the keys that changed. */
    private var persisted: Map<String, String> = emptyMap()

    override suspend fun persist() {
        writeMutex.withLock {
            // Snapshot inside the lock so a slow earlier writer can never commit a stale value last.
            val values = snapshot()
            val changed = values.filter { (key, value) -> persisted[key] != value }
            if (changed.isEmpty()) return
            withContext(Dispatchers.IO) { store.putSettings(changed) }
            persisted = values
        }
    }

    companion object {
        suspend fun open(store: AppStore): AppStoreSettings = withContext(Dispatchers.IO) {
            val values = store.settings()
            AppStoreSettings(store).apply {
                seed(values)
                persisted = values
            }
        }
    }
}
