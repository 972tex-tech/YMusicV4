package io.ymusic.mse

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.image.BufferedImage

actual class MseVideoSurface {
    private val _frame = MutableStateFlow<BufferedImage?>(null)
    val frame: StateFlow<BufferedImage?> = _frame.asStateFlow()

    internal fun publish(frame: BufferedImage) {
        _frame.value = frame
    }

    internal fun clear() {
        _frame.value = null
    }
}
