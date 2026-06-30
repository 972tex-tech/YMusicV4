package kmpffmpeg

import kmpcommon.uri.Uri

/**
 * Maps a logical destination [Uri] to an [FfOutput] ffmpeg can write — the write-side mirror of
 * [FfInputResolver]. The default [Passthrough] hands the URI text to ffmpeg (`file:`/`ymix:`). A
 * platform that must proxy a destination ffmpeg can't address — Android's `content://` (opened `"w"`),
 * or an in-memory sink — supplies its own resolver returning an [FfOutput] backed by `pipe:1` or a
 * ymix write server.
 *
 * [resolve] is the PRE-start phase: open the destination and launch any write server here, so it is
 * listening before ffmpeg connects. The caller must guarantee [FfOutput.close] runs even if the process
 * fails to start, so a server opened here is never leaked.
 *
 * Not yet consumed by anything — added ahead of the transcode/merge work (see the I/O API design note).
 */
fun interface FfOutputResolver {
    suspend fun resolve(uri: Uri): FfOutput

    companion object {
        /** Hands the URI to ffmpeg directly — no proxying. See [toFfmpegSpec] for the `file:` handling. */
        val Passthrough: FfOutputResolver = FfOutputResolver { FfOutput.passthrough(it.toFfmpegSpec()) }
    }
}
