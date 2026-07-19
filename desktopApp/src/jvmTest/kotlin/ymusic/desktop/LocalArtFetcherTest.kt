package ymusic.desktop

import androidx.compose.ui.unit.IntSize
import kmpffmpeg.metadata.FfmpegMetadataGetter
import kotlinx.coroutines.runBlocking
import ymusic.ui.localArtFetcher
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The whole local-art pipeline on a real file: ffmpeg muxes a cover into an mp3, the fetcher
 *  extracts and decodes it. Skipped when no ffmpeg binary is on this machine. */
class LocalArtFetcherTest {
    @Test
    fun extractsAndDecodesTheEmbeddedCover() {
        val ffmpeg = findFfmpeg() ?: return // no binary on this machine — nothing to test against
        val dir = File(System.getProperty("java.io.tmpdir"), "local-art-test-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val cover = File(dir, "cover.png")
            val tone = File(dir, "tone.mp3")
            val song = File(dir, "song.mp3")
            run(ffmpeg, "-y", "-f", "lavfi", "-i", "color=red:s=64x48", "-frames:v", "1", cover.path)
            run(ffmpeg, "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=1", tone.path)
            run(
                ffmpeg, "-y", "-i", tone.path, "-i", cover.path,
                "-map", "0:a", "-map", "1:v", "-c", "copy",
                "-id3v2_version", "3", "-disposition:v:0", "attached_pic", song.path,
            )

            val fetcher = localArtFetcher(FfmpegMetadataGetter(ffmpeg))
            val decoded = runBlocking {
                fetcher.fetch("art://pic/file://${song.path}", cacheKey = "k", size = IntSize(64, 64))
            }
            assertNotNull(decoded, "fetcher returned null for its own scheme")
            assertEquals(64, decoded.bitmap.width)
            assertEquals(48, decoded.bitmap.height)

            val other = runBlocking { fetcher.fetch("https://example.com/x.png", "k", IntSize(64, 64)) }
            assertNull(other, "fetcher must decline non-art urls")
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun run(vararg cmd: String) {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        check(p.waitFor() == 0) { "command failed: ${cmd.joinToString(" ")}\n$out" }
    }
}
