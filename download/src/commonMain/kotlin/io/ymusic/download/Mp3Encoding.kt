package io.ymusic.download

/** How an MP3 download is encoded — YMusic legacy's two LAME presets. [kbps] is the bitrate shown for it. */
enum class Mp3Encoding(val kbps: Int) {
    /** LAME ABR at 320 kbps. */
    ABR_320(320),

    /** LAME VBR V0 (`-q:a 0`); averages about 256 kbps on YouTube audio. */
    VBR_V0(256),
    ;

    companion object {
        fun of(sourceBitrate: Boolean): Mp3Encoding = if (sourceBitrate) VBR_V0 else ABR_320
    }
}
