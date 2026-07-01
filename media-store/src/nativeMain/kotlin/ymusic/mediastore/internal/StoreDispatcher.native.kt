package ymusic.mediastore.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Native has no Dispatchers.IO; Default is the conventional choice for the sqliter (blocking) driver.
internal actual fun storeDispatcher(): CoroutineDispatcher = Dispatchers.Default
