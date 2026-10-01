package io.ymusic.ui.sponsorblock

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

private val dbDispatcher: CoroutineDispatcher =
    Executors.newSingleThreadExecutor { r -> Thread(r, "sponsor-block-db").apply { isDaemon = true } }
        .asCoroutineDispatcher()

internal actual fun sponsorBlockDispatcher(): CoroutineDispatcher = dbDispatcher
