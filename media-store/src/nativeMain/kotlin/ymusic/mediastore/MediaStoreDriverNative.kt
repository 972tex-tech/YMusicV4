package ymusic.mediastore

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import ymusic.mediastore.db.MediaStoreDatabase

/**
 * A native (sqliter) driver with the schema created and foreign keys enabled. A file-backed database
 * is placed in the platform's default databases directory; pass [inMemory] = true for an ephemeral
 * in-memory database (used by tests).
 *
 * @param name database file name (also the in-memory database's key).
 */
fun mediaStoreNativeDriver(name: String = "media_store.db", inMemory: Boolean = false): SqlDriver =
    NativeSqliteDriver(
        schema = MediaStoreDatabase.Schema,
        name = name,
        onConfiguration = { config ->
            config.copy(
                inMemory = inMemory,
                extendedConfig = config.extendedConfig.copy(foreignKeyConstraints = true),
            )
        },
    )
