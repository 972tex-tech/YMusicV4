package io.ymusic.mediascanner.internal

import io.ymusic.mediascanner.DocumentInfo
import kmpcommon.uri.Uri
import kmpffmpeg.metadata.ContainerMetadata
import kmpffmpeg.metadata.MediaFileMetadata
import kmpffmpeg.metadata.MetadataMap
import kmpffmpeg.metadata.StreamMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetadataMappingTest {

    private fun file(name: String) = DocumentInfo.File(
        uri = Uri.parse("file:///$name"),
        name = name,
        mimeType = "audio/mpeg",
        size = 123L,
        lastModified = 456L,
    )

    private fun metadata(
        containerTags: Map<String, String?> = emptyMap(),
        streamTags: Map<String, String?> = emptyMap(),
    ) = MediaFileMetadata(
        uri = Uri.parse("file:///x.mp3"),
        container = ContainerMetadata("mp3", null, null, null, MetadataMap(containerTags)),
        streams = listOf(
            StreamMetadata.Audio(0, "mp3", null, null, null, null, MetadataMap(streamTags), emptySet()),
        ),
    )

    @Test
    fun videoId_picks_first_matching_composer_across_all_tag_bags() {
        // The container's composer is non-blank but carries no id; the stream's composer does.
        val draft = metadata(
            containerTags = mapOf("composer" to "Some Composer Name"),
            streamTags = mapOf("composer" to "dQw4w9WgXcQ|140"),
        ).toMediaItemDraft(file("song.mp3"))

        assertEquals("dQw4w9WgXcQ", draft.youtubeVid)
    }

    @Test
    fun videoId_falls_back_to_filename_pattern() {
        val draft = metadata().toMediaItemDraft(file("My Song [dQw4w9WgXcQ].mp3"))
        assertEquals("dQw4w9WgXcQ", draft.youtubeVid)
    }

    @Test
    fun videoId_is_null_when_nothing_matches() {
        val draft = metadata(containerTags = mapOf("composer" to "Just A Name"))
            .toMediaItemDraft(file("plain.mp3"))
        assertNull(draft.youtubeVid)
    }

    @Test
    fun title_prefers_tag_then_falls_back_to_filename() {
        assertEquals(
            "Tagged Title",
            metadata(containerTags = mapOf("title" to "Tagged Title")).toMediaItemDraft(file("a.mp3")).title,
        )
        assertEquals("a", metadata().toMediaItemDraft(file("a.mp3")).title)
    }
}
