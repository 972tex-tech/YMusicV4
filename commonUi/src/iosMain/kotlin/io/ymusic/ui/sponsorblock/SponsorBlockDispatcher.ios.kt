package io.ymusic.ui.sponsorblock

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private val dbDispatcher: CoroutineDispatcher = newSingleThreadContext("sponsor-block-db")

internal actual fun sponsorBlockDispatcher(): CoroutineDispatcher = dbDispatcher
