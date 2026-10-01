package io.ymusic.ui.sponsorblock

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/** Schema for the SponsorBlock cache + per-video whitelist. Matches legacy `io.ymusic.android.sponsor_block`. */
object SponsorBlockSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long get() = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        driver.execute(
            null,
            """
            CREATE TABLE sponsorBlockCache (
                videoId TEXT NOT NULL PRIMARY KEY,
                segments TEXT NOT NULL,
                updatedAt INTEGER NOT NULL,
                usedAt INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE sponsorBlockWhiteList (
                videoId TEXT NOT NULL PRIMARY KEY
            )
            """.trimIndent(),
            0,
        )
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Unit
}
