package ymusic.download

import kmpcommon.error.TechnicalException
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [OkioDownloadStorage.publish]: the happy file:// move, and the coded errors when a content:// destination
 *  has no [ContentPublisher] or the publisher fails. */
class OkioDownloadStorageTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "ymusic-storage-${System.nanoTime()}").also { it.mkdirs() }
    private val fs = FileSystem.SYSTEM
    private val scratchDir = (root.path + "/scratch").toPath()
    private val outputDir = (root.path + "/out").toPath()

    @AfterTest fun cleanup() { root.deleteRecursively() }

    private fun storage(publisher: ContentPublisher? = null) =
        OkioDownloadStorage(fs, scratchDir, outputDir, publisher)

    private fun scratchWith(storage: OkioDownloadStorage, bytes: ByteArray): DownloadScratch =
        storage.scratch("t1", "out").also { s -> s.appendSink().use { it.write(bytes) } }

    @Test fun publish_moves_the_scratch_into_a_file_destination() = runBlocking {
        val storage = storage()
        val dest = (root.path + "/library").toPath()
        val scratch = scratchWith(storage, byteArrayOf(1, 2, 3, 4))
        assertEquals(4, scratch.length(), "scratch wrote 4 bytes")

        val uri = storage.publish(scratch, "song.m4a", "file://$dest")

        assertEquals("file://$dest/song.m4a", uri)
        assertTrue(fs.exists(dest / "song.m4a"))
        assertEquals(4, fs.metadata(dest / "song.m4a").size)
        assertTrue(!fs.exists(scratch.path.toPath()), "scratch moved, not copied")
    }

    @Test fun publish_to_content_destination_without_a_publisher_is_coded() = runBlocking {
        val e = assertFailsWith<TechnicalException> {
            storage().publish(scratchWith(storage(), byteArrayOf(1)), "song.m4a", "content://tree/downloads")
        }
        assertEquals("E70031", e.code)
    }

    @Test fun publish_wraps_a_failing_content_publisher_as_coded() = runBlocking {
        val boom = ContentPublisher { _, _, _ -> throw RuntimeException("SAF said no") }
        val storage = storage(boom)
        val e = assertFailsWith<TechnicalException> {
            storage.publish(scratchWith(storage, byteArrayOf(1)), "song.m4a", "content://tree/downloads")
        }
        assertEquals("E70032", e.code)
    }
}
