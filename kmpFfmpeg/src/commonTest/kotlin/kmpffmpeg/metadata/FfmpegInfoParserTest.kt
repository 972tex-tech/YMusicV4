package kmpffmpeg.metadata

import kmpcommon.uri.Uri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure parser tests over real `ffmpeg -i` stderr captures (no process spawned), so they run on every
 * target — including iOS, where the metadata model still has to work even though nothing can exec.
 */
class FfmpegInfoParserTest {

    @Test
    fun parsesFlacContainerAndAudioStream() {
        val out = """
            Input #0, flac, from 'song.flac':
              Metadata:
                title           : Midnight Drive
                artist          : The Kotlin Quartet
                album           : Multiplatform Sessions
                date            : 2024
                genre           : Synthwave
                encoder         : Lavf63.2.100
              Duration: 00:00:02.00, start: 0.000000, bitrate: 156 kb/s
              Stream #0:0: Audio: flac, 44100 Hz, mono, s16
            At least one output file must be specified
        """.trimIndent()

        val meta = FfmpegInfoParser.parse(Uri.parse("file:song.flac"), out)

        assertEquals("file:song.flac", meta.uri.toString())
        assertEquals("flac", meta.container.name)
        assertEquals(2000, meta.container.duration)
        assertEquals(156_000, meta.container.bitrate)
        assertNull(meta.container.size)
        with(meta.container.metadataMap) {
            assertEquals("Midnight Drive", title)
            assertEquals("The Kotlin Quartet", artist)
            assertEquals("Multiplatform Sessions", album)
            assertEquals("2024", year)
            assertEquals("Synthwave", genre)
        }

        assertEquals(1, meta.streams.size)
        val audio = meta.streams.single() as StreamMetadata.Audio
        assertEquals("flac", audio.codec)
        assertEquals(44100, audio.sample)
        assertEquals(1, audio.channels)
        assertNull(audio.bitrate)      // raw FLAC stream prints no per-stream kb/s
        assertNull(audio.duration)
        assertTrue(audio.disposition.isEmpty())
        assertFalse(meta.hasVideo())
    }

    @Test
    fun parsesMp4WithStreamBitrateTrackAndDefaultDisposition() {
        val out = """
            Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'song.m4a':
              Metadata:
                major_brand     : M4A
                minor_version   : 512
                compatible_brands: M4A isomiso2
                title           : Midnight Drive
                artist          : The Kotlin Quartet
                track           : 3/10
              Duration: 00:00:02.00, start: 0.000000, bitrate: 76 kb/s
              Stream #0:0[0x1](und): Audio: aac (LC) (mp4a / 0x6134706D), 44100 Hz, mono, fltp, 70 kb/s (default)
                Metadata:
                  handler_name    : SoundHandler
            At least one output file must be specified
        """.trimIndent()

        val meta = FfmpegInfoParser.parse(Uri.parse("file:test"), out)

        assertEquals("mov,mp4,m4a,3gp,3g2,mj2", meta.container.name)
        assertEquals("M4A isomiso2", meta.container.metadataMap.all["compatible_brands"])
        assertEquals("3/10", meta.container.metadataMap.track)

        val audio = meta.streams.single() as StreamMetadata.Audio
        assertEquals("aac", audio.codec)
        assertEquals(70_000, audio.bitrate)
        assertEquals(1, audio.channels)
        assertTrue(StreamMetadata.DISPOSITION_DEFAULT in audio.disposition)
        assertEquals("SoundHandler", audio.metadataMap.all["handler_name"])
        assertNull(audio.metadataMap.language) // (und) is dropped
    }

    @Test
    fun resolvesUppercaseMatroskaTagsCaseInsensitively() {
        val out = """
            Input #0, matroska,webm, from 'song.mka':
              Metadata:
                title           : Midnight Drive
                GENRE           : Synthwave
                ARTIST          : The Kotlin Quartet
                ALBUM           : Multiplatform Sessions
                DATE            : 2024
              Duration: 00:00:02.02, start: 0.000000, bitrate: 75 kb/s
              Stream #0:0: Audio: aac (LC), 44100 Hz, mono, fltp
                Metadata:
                  DURATION        : 00:00:02.023000000
            At least one output file must be specified
        """.trimIndent()

        val meta = FfmpegInfoParser.parse(Uri.parse("file:test"), out)

        assertEquals("matroska,webm", meta.container.name)
        assertEquals(2020, meta.container.duration) // 00:00:02.02
        with(meta.container.metadataMap) {
            assertEquals("The Kotlin Quartet", artist) // ARTIST resolved despite case
            assertEquals("Multiplatform Sessions", album)
            assertEquals("Synthwave", genre)
            assertEquals("2024", year)
        }
        val audio = meta.streams.single() as StreamMetadata.Audio
        assertEquals("aac", audio.codec)
        assertNull(audio.bitrate)
    }

    @Test
    fun stereoMapsToTwoChannelsWithStreamBitrate() {
        val out = """
            Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'stereo.m4a':
              Duration: 00:00:02.00, start: 0.000000, bitrate: 131 kb/s
              Stream #0:0[0x1](und): Audio: aac (LC) (mp4a / 0x6134706D), 44100 Hz, stereo, fltp, 124 kb/s (default)
        """.trimIndent()

        val audio = FfmpegInfoParser.parse(Uri.parse("file:test"), out).streams.single() as StreamMetadata.Audio
        assertEquals(2, audio.channels)
        assertEquals(124_000, audio.bitrate)
    }

    @Test
    fun attachedCoverIsVideoButNotCountedAsVideo() {
        val out = """
            Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'withcover.m4a':
              Metadata:
                title           : Midnight Drive
              Duration: 00:00:02.00, start: 0.000000, bitrate: 76 kb/s
              Stream #0:0[0x1](und): Audio: aac (LC) (mp4a / 0x6134706D), 44100 Hz, mono, fltp, 70 kb/s (default)
                Metadata:
              Stream #0:1[0x0]: Video: png, rgb24(pc, gbr/unknown/unknown), 32x32 [SAR 1:1 DAR 1:1], 90k tbr, 90k tbn (attached pic)
            At least one output file must be specified
        """.trimIndent()

        val meta = FfmpegInfoParser.parse(Uri.parse("file:test"), out)
        assertEquals(2, meta.streams.size)

        val video = meta.streams.filterIsInstance<StreamMetadata.Video>().single()
        assertEquals("png", video.codec)
        assertEquals(1, video.index) // the `N` of `Stream #0:1`
        assertEquals(32, video.width)
        assertEquals(32, video.height)
        assertTrue(StreamMetadata.DISPOSITION_ATTACHED_PIC in video.disposition)
        assertTrue(video.isAttachedThumbnail())
        assertFalse(meta.hasVideo()) // attached cover art doesn't make it "a video"
    }

    @Test
    fun streamIndexIsFfmpegIndexNotListPosition() {
        // A Data stream sits between the audio and the cover. We don't model Data streams, so the cover
        // is at list position 1 but ffmpeg index 2 — `index` records the real #0:N, not the position.
        val out = """
            Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'withdata.m4a':
              Duration: 00:00:02.00, start: 0.000000, bitrate: 76 kb/s
              Stream #0:0[0x1](und): Audio: aac (LC), 44100 Hz, mono, fltp, 70 kb/s (default)
              Stream #0:1[0x2](und): Data: bin_data (text / 0x74786574)
              Stream #0:2[0x3]: Video: mjpeg, yuvj420p, 600x600, 90k tbr (attached pic)
        """.trimIndent()

        val meta = FfmpegInfoParser.parse(Uri.parse("file:test"), out)
        assertEquals(2, meta.streams.size) // Data dropped
        val cover = meta.streams.filterIsInstance<StreamMetadata.Video>().single()
        assertEquals(1, meta.streams.indexOf(cover)) // its position in the list…
        assertEquals(2, cover.index)                 // …is NOT its ffmpeg index
    }

    @Test
    fun realVideoStreamParsesDimensionsAndCountsAsVideo() {
        val out = """
            Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'video.mp4':
              Duration: 00:00:01.00, start: 0.000000, bitrate: 161 kb/s
              Stream #0:0[0x1](und): Video: mpeg4 (Simple Profile) (mp4v / 0x7634706D), yuv420p, 320x240 [SAR 1:1 DAR 4:3], 153 kb/s, 10 fps, 10 tbr, 10240 tbn (default)
        """.trimIndent()

        val meta = FfmpegInfoParser.parse(Uri.parse("file:test"), out)
        val video = meta.streams.single() as StreamMetadata.Video
        assertEquals("mpeg4", video.codec)
        assertEquals(320, video.width)  // not fooled by the 0x7634706D hex tag
        assertEquals(240, video.height)
        assertEquals(10.0, video.fps)
        assertTrue(meta.hasVideo())
    }

    @Test
    fun throwsWhenNoInputBlock() {
        val out = "song.xyz: Invalid data found when processing input\n"
        assertFailsWith<FfmpegInfoParseException> { FfmpegInfoParser.parse(Uri.parse("file:test"), out) }
    }
}
