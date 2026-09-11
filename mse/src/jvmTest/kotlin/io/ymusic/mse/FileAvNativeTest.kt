package io.ymusic.mse

import kotlin.test.Test
import kotlin.test.assertTrue

class FileAvNativeTest {
    @Test
    fun opensFixtureAndReportsDuration() {
        val path = fixture() ?: return
        val h = FileAvNative.create()
        assertTrue(h != 0L, "file_av_create")
        try {
            val r = FileAvNative.open(h, path)
            assertTrue(r == 0, FileAvNative.lastError(h))
            assertTrue(FileAvNative.duration(h) > 0.0, "duration")
            assertTrue(FileAvNative.streams(h).isNotEmpty(), "streams")
        } finally {
            FileAvNative.destroy(h)
        }
    }

    private fun fixture(): String? {
        val roots = listOf(
            "../kmpFfmpeg/testdata/sample_with_metadata.m4a",
            "kmpFfmpeg/testdata/sample_with_metadata.m4a",
        )
        return roots.map { java.io.File(it).canonicalFile }.firstOrNull { it.isFile }?.absolutePath
    }
}
