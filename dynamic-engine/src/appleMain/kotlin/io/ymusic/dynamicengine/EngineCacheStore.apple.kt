package io.ymusic.dynamicengine

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.ymusic.dynamicengine.db.CacheDatabase
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970

/**
 * A [DiskCacheStore] for the scraping engine on iOS/macOS: the metadata index in a SQLite file and
 * the value blobs under [directory]. Hand the result to [DynamicEngineManager] so the engine's
 * `Cache` protocol (InnerTube session params, feed snapshots) is backed by real storage.
 *
 * Application Support, not the caches directory: the Recommended feed lives in this store and has
 * no other copy, so an OS purge would silently delete user-visible content.
 */
fun appleEngineCacheStore(directory: Path = defaultEngineCacheDirectory(), logger: kmpcommon.log.Logger? = null): CacheStore {
    FileSystem.SYSTEM.createDirectories(directory)
    val driver = NativeSqliteDriver(
        schema = CacheDatabase.Schema,
        name = "engine-cache.db",
        onConfiguration = { config ->
            config.copy(extendedConfig = config.extendedConfig.copy(basePath = directory.toString()))
        },
    )
    return DiskCacheStore(
        driver,
        directory / "blobs",
        FileSystem.SYSTEM,
        now = { (NSDate().timeIntervalSince1970 * 1000).toLong() },
        logger = logger,
    )
}

/**
 * `<Application Support>/[name]`. macOS shares one Application Support across apps, so the name is
 * part of the path rather than the directory itself.
 */
fun defaultEngineCacheDirectory(name: String = "ymusic-engine-cache"): Path =
    (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String)
        .toPath()
        .resolve(name)
