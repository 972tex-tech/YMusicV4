package io.ymusic.download.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun downloadDbDispatcher(): CoroutineDispatcher = Dispatchers.IO
