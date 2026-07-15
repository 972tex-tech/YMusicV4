package ymusic.dynamicengine

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import ymusic.dynamicengine.db.CacheDatabase
import java.io.File

/**
 * A [DiskCacheStore] for the scraping engine on desktop (JVM): the metadata index in a SQLite file
 * and the value blobs under [directory]. Hand the result to [DynamicEngineManager] so the engine's
 * `Cache` protocol (InnerTube session params, feed snapshots) is backed by real storage.
 */
fun desktopEngineCacheStore(directory: File): CacheStore {
    directory.mkdirs()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${File(directory, "engine-cache.db")}", schema = CacheDatabase.Schema)
    val blobs: Path = directory.resolve("blobs").toOkioPath()
    return DiskCacheStore(driver, blobs, FileSystem.SYSTEM, now = { System.currentTimeMillis() })
}
