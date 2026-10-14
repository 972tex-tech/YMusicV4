package io.ymusic.ytmedia.sabr

import io.ymusic.ytmedia.proto.ProtoReader
import io.ymusic.ytmedia.proto.string
import io.ymusic.ytmedia.proto.varint

/**
 * Lenient decoders for the small SABR control-part payloads (parse errors → null, matching
 * legacy's tryParse* helpers — a malformed control part must not kill the stream).
 */
object SabrParts {
    /** UMP part 44 payload → error message. */
    fun parseErrorMessage(payload: ByteArray): String? = parseStringField1(payload)

    /** UMP part 43 payload → replacement /videoplayback URL. */
    fun parseRedirectUrl(payload: ByteArray): String? = parseStringField1(payload)

    /** UMP part 58 payload → status. 1 = ok; 2 = attestation pending; 3 = invalid PO token, media will stop. */
    fun parseStreamProtectionStatus(payload: ByteArray): Int? = runCatching {
        ProtoReader(payload).readAllFields().varint(1)?.toInt()
    }.getOrNull()

    private fun parseStringField1(payload: ByteArray): String? = runCatching {
        ProtoReader(payload).readAllFields().string(1)
    }.getOrNull()
}
