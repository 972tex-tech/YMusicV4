package ymusic.ytmedia.sabr

import okio.Buffer
import ymusic.ytmedia.proto.ProtoReader
import ymusic.ytmedia.proto.ProtoWriter
import ymusic.ytmedia.proto.message

/**
 * Serializes a SABR download's resume point — the last completed audio/video [MediaHeader] — to a small
 * blob the caller persists next to the partial file. On resume it's decoded and fed back to
 * [SabrSession.download] (resumeAudio/resumeVideo) so the session declares its buffered range and the
 * server continues from the next segment instead of re-sending from zero. Reuses [MediaHeader.encode].
 */
object SabrResumeCursor {

    fun encode(audio: MediaHeader?, video: MediaHeader?): ByteArray {
        val writer = ProtoWriter()
        audio?.let { writer.message(1, it.encode()) }
        video?.let { writer.message(2, it.encode()) }
        return writer.toByteArray()
    }

    /** (audio, video) headers; either null if absent or the blob is unparseable. */
    fun decode(bytes: ByteArray): Pair<MediaHeader?, MediaHeader?> = runCatching {
        val fields = ProtoReader(Buffer().apply { write(bytes) }, bytes.size.toLong()).readAllFields()
        fields.message(1)?.let { MediaHeader.from(it) } to fields.message(2)?.let { MediaHeader.from(it) }
    }.getOrDefault(null to null)
}
