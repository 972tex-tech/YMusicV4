package io.ymusic.ui.playhistory

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

private val dbDispatcher: CoroutineDispatcher =
    Executors.newSingleThreadExecutor { r -> Thread(r, "play-history-db").apply { isDaemon = true } }
        .asCoroutineDispatcher()

internal actual fun playHistoryDispatcher(): CoroutineDispatcher = dbDispatcher
