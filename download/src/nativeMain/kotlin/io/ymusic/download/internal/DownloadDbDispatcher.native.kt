package io.ymusic.download.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Native (apple/linux) has no Dispatchers.IO; Default is the documented substitute (see :media-store).
internal actual fun downloadDbDispatcher(): CoroutineDispatcher = Dispatchers.Default
