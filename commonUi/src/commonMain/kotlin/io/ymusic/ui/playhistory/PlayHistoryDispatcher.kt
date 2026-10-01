package io.ymusic.ui.playhistory

import kotlinx.coroutines.CoroutineDispatcher

/** Single thread for all play-history SQLite work (JdbcSqliteDriver is thread-local). */
internal expect fun playHistoryDispatcher(): CoroutineDispatcher
