package io.ymusic.ui.sponsorblock

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

/** Native (sqliter) [SqlDriver] for SponsorBlock, in the platform default DB dir. */
fun sponsorBlockNativeDriver(name: String = "sponsor-block.db"): SqlDriver =
    NativeSqliteDriver(schema = SponsorBlockSchema, name = name)
