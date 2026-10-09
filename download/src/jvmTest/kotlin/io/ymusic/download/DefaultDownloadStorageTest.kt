package io.ymusic.download

import kmpcommon.uri.Uri
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import projectcommon.error.TechnicalException
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [DefaultDownloadStorage.publish]: the happy file:// move, the coded error for an unsupported scheme, and
 *  that a subclass' [DefaultDownloadStorage.publishToUri] handles non-file destinations. */
class DefaultDownloadStorageTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "ymusic-storage-${System.nanoTime()}").also { it.mkdirs() }
    private val fs = FileSystem.SYSTEM
    private val scratchDir = (root.path + "/scratch").toPath()

    @AfterTest fun cleanup() { root.deleteRecursively() }

    private fun storage() = DefaultDownloadStorage(fs, scratchDir)

    private fun DownloadStorage.scratchWith(bytes: ByteArray): DownloadScratch =
        scratch("t1", "out").also { s -> s.appendSink().use { it.write(bytes) } }

    @Test fun publish_moves_the_scratch_into_a_file_destination() = runBlocking {
        val storage = storage()
        val dest = (root.path + "/library").toPath()
        val scratch = storage.scratchWith(byteArrayOf(1, 2, 3, 4))
        assertEquals(4, scratch.length(), "scratch wrote 4 bytes")

        val uri = storage.publish(scratch, "song.m4a", "file://$dest")

        assertEquals("file://$dest/song.m4a", uri)
        assertTrue(fs.exists(dest / "song.m4a"))
        assertEquals(4, fs.metadata(dest / "song.m4a").size)
        assertTrue(!fs.exists(scratch.path.toPath()), "scratch moved, not copied")
    }

    @Test fun publish_to_an_unsupported_scheme_is_coded() = runBlocking {
        val storage = storage()
        val e = assertFailsWith<TechnicalException> {
            storage.publish(storage.scratchWith(byteArrayOf(1)), "song.m4a", "content://tree/downloads")
        }
        assertEquals("E70031", e.code)
    }

    @Test fun subclass_handles_a_custom_scheme_and_a_failure_is_coded() = runBlocking {
        // A subclass that "publishes" to content:// by recording the call.
        var seen: String? = null
        val ok = object : DefaultDownloadStorage(fs, scratchDir) {
            override suspend fun publishToUri(scratchPath: String, fileName: String, destinationFolder: Uri): String {
                seen = "$destinationFolder/$fileName"
                return "content://out/$fileName"
            }
        }
        assertEquals("content://out/song.m4a", ok.publish(ok.scratchWith(byteArrayOf(1)), "song.m4a", "content://tree/dl"))
        assertEquals("content://tree/dl/song.m4a", seen)

        // A subclass whose publish throws is surfaced as a coded error, not a bare exception.
        val boom = object : DefaultDownloadStorage(fs, scratchDir) {
            override suspend fun publishToUri(scratchPath: String, fileName: String, destinationFolder: Uri): String =
                throw RuntimeException("SAF said no")
        }
        val e = assertFailsWith<TechnicalException> {
            boom.publish(boom.scratchWith(byteArrayOf(1)), "song.m4a", "content://tree/dl")
        }
        assertEquals("E70032", e.code)
    }
}
