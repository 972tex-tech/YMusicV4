package io.ymusic.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FfmpegProgressTest {

    @Test
    fun parses_out_time_us_line() {
        assertEquals(12_500_000L, parseFfmpegProgressUs("out_time_us=12500000"))
    }

    @Test
    fun ignores_other_progress_keys() {
        assertNull(parseFfmpegProgressUs("frame=42"))
        assertNull(parseFfmpegProgressUs("out_time_ms=12500000")) // the _ms twin — we key on _us only
        assertNull(parseFfmpegProgressUs("progress=continue"))
        assertNull(parseFfmpegProgressUs("bitrate=128.0kbits/s"))
    }

    @Test
    fun tolerates_na_and_blank_values() {
        assertNull(parseFfmpegProgressUs("out_time_us=N/A"))
        assertNull(parseFfmpegProgressUs("out_time_us="))
    }
}
