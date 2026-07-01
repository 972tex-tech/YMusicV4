package ymusic.mediastore.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun storeDispatcher(): CoroutineDispatcher = Dispatchers.IO
