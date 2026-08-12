package io.ymusic.dynamicengine

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ymusic.dynamicengine.db.CacheDatabase
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * A [DiskCacheStore] for the scraping engine, app-private: the metadata index in a SQLite file and
 * the value blobs under the app cache directory. Hand the result to [DynamicEngineManager] so the
 * engine's `Cache` protocol (InnerTube session params, feed snapshots) is backed by real storage.
 */
fun androidEngineCacheStore(context: Context, subdir: String = "engine-cache", logger: kmpcommon.log.Logger? = null): CacheStore {
    val driver = AndroidSqliteDriver(CacheDatabase.Schema, context, "engine-cache.db")
    val blobs = context.cacheDir.resolve(subdir).toOkioPath()
    return DiskCacheStore(driver, blobs, FileSystem.SYSTEM, now = { System.currentTimeMillis() }, logger = logger)
}
