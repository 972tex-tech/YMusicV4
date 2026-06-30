package kmpffmpeg.metadata

/**
 * File-level (container/format) metadata: the demuxer name as ffmpeg reports it (`flac`,
 * `mov,mp4,m4a,3gp,3g2,mj2`, `matroska,webm`, …), overall duration/bitrate, and the container-level
 * tag bag. Ported from YMusic-legacy. [duration] is in milliseconds; [bitrate] in bits per second.
 */
data class ContainerMetadata(
    val name: String,
    val size: Long?,
    val duration: Long?,
    val bitrate: Long?,
    val metadataMap: MetadataMap
)
