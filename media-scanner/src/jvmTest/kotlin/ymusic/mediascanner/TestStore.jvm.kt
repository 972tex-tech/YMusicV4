package ymusic.mediascanner

import app.cash.sqldelight.db.SqlDriver
import ymusic.mediastore.mediaStoreJdbcDriver

internal actual fun newTestDriver(): SqlDriver = mediaStoreJdbcDriver(":memory:")
