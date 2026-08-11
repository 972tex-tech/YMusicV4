package io.ymusic.mediascanner

import app.cash.sqldelight.db.SqlDriver
import io.ymusic.mediastore.mediaStoreJdbcDriver

internal actual fun newTestDriver(): SqlDriver = mediaStoreJdbcDriver(":memory:")
