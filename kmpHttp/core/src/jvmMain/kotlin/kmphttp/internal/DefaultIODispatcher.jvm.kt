package kmphttp.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal actual val DefaultIODispatcher: CoroutineDispatcher = Dispatchers.IO
