package io.ymusic.mediastore

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ymusic.mediastore.db.MediaStoreDatabase

/**
 * An Android driver with the schema created and foreign keys enabled.
 *
 * @param name database file name, or null for an in-memory database.
 */
fun mediaStoreAndroidDriver(context: Context, name: String? = "media_store.db"): SqlDriver =
    AndroidSqliteDriver(
        schema = MediaStoreDatabase.Schema,
        context = context,
        name = name,
        callback = object : AndroidSqliteDriver.Callback(MediaStoreDatabase.Schema) {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
                // WAL + synchronous=NORMAL so a scan's many small writes don't fsync a rollback
                // journal per commit and saturate the store's single DB thread. WAL also lets a read
                // see the last checkpoint instead of queueing behind the writer.
                db.query("PRAGMA journal_mode=WAL").use { it.moveToFirst() }
                db.execSQL("PRAGMA synchronous=NORMAL")
            }
        },
    )
