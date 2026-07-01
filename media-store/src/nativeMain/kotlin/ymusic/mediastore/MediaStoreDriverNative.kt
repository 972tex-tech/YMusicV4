package ymusic.mediastore

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import ymusic.mediastore.db.MediaStoreDatabase

/**
 * A native (sqliter) driver with the schema created and foreign keys enabled. The database file is
 * placed in the platform's default databases directory.
 *
 * @param name database file name.
 */
fun mediaStoreNativeDriver(name: String = "media_store.db"): SqlDriver =
    NativeSqliteDriver(
        schema = MediaStoreDatabase.Schema,
        name = name,
        onConfiguration = { config ->
            config.copy(extendedConfig = config.extendedConfig.copy(foreignKeyConstraints = true))
        },
    )
