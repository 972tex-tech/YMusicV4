package io.ymusic.ui.playhistory

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private val dbDispatcher: CoroutineDispatcher = newSingleThreadContext("play-history-db")

internal actual fun playHistoryDispatcher(): CoroutineDispatcher = dbDispatcher
