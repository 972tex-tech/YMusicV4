package kmpcommon

import kotlin.test.Test
import kotlin.test.assertEquals

class NumberXTest {
    @Test
    fun int_size_helpers_do_not_overflow_intermediate() {
        // These overflowed Int before the widening and went negative.
        assertEquals(2048L * 1024 * 1024 * 1024, 2048.gigabytes)
        assertEquals(4096L * 1024 * 1024, 4096.megabytes)
        assertEquals(3L * 1024 * 1024 * 1024, 3.gigabytes)
        assertEquals(1L * 1024, 1.kilobytes)
    }
}
