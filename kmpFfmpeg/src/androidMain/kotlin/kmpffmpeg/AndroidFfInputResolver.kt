package kmpffmpeg

import android.content.ContentResolver
import kmpcommon.uri.Uri

/**
 * An [FfInputResolver] that proxies Android `content://` (and `file://`) sources for ffmpeg: it opens
 * the uri through [ContentResolver] and serves it over the `ymix` protocol. Other schemes pass through
 * unchanged.
 *
 * ```
 * FfmpegMetadataGetter(ffmpegBinary, AndroidFfInputResolver(context.contentResolver))
 *     .getMetadata(Uri.parse("content://media/external/audio/media/42"))
 * ```
 */
class AndroidFfInputResolver(
    private val contentResolver: ContentResolver,
) : FfInputResolver {
    override suspend fun resolve(uri: Uri): FfInput = when (uri.scheme?.toString()) {
        ContentResolver.SCHEME_CONTENT -> {
            val androidUri = android.net.Uri.parse(uri.toString())
            // The ymix server must be listening before ffmpeg starts (ffmpeg connects to it as a
            // client), so it is started here in resolve() — the pre-start phase — not in onProcessStarted.
            val server = FfYmixProtocolServer(contentResolver, androidUri).apply { start() }
            object : FfInput {
                override val spec: String = server.url
                // Give the server the process so a fatal proxy error fails it with the real cause.
                override fun onProcessStarted(process: KmpProcess) = server.onProcessStarted(process)
                override fun close() = server.close()
            }
        }
        // ffmpeg opens plain files itself — no proxy needed.
        ContentResolver.SCHEME_FILE -> FfInput.passthrough("file:${uri.path}")
        else -> FfInput.passthrough(uri.toString())
    }
}
