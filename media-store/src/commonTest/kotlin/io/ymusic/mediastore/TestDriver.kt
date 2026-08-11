package io.ymusic.mediastore

import app.cash.sqldelight.db.SqlDriver

/** A fresh in-memory database with the schema created and foreign keys enabled. */
internal expect fun testSqlDriver(): SqlDriver
