package io.ymusic.mediastore

import app.cash.sqldelight.db.SqlDriver

internal actual fun testSqlDriver(): SqlDriver = mediaStoreJdbcDriver(":memory:")
