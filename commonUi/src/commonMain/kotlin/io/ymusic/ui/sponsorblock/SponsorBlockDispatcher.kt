package io.ymusic.ui.sponsorblock

import kotlinx.coroutines.CoroutineDispatcher

/** Single thread for SponsorBlock SQLite work (JdbcSqliteDriver is thread-local). */
internal expect fun sponsorBlockDispatcher(): CoroutineDispatcher
