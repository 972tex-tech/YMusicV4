package ymusic.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.encodeUtf8
import java.io.File
import java.io.FileNotFoundException

/**
 * `content://<pkg>.artwork/art?uri=<encoded target>` — read-only artwork for cross-process
 * consumers that cannot fetch our urls themselves. Android Auto / AAOS resolve queue and browse
 * icons ONLY from local content uris (they never download https, and `art://` is app-internal),
 * so [PlaybackService] publishes every artworkUri in this wrapped form.
 *
 * The provider is exported (Auto holds no permission of ours), so [openFile] trusts nothing: the
 * target must pass [isAllowedArtworkTarget] — our own `art://pic/<id>` or an https image on a
 * YouTube cdn host — anything else 404s. Resolution rides the shared app
 * [ymusic.imageloader.ImageLoader] (same fetchers, memory + scaled-disk caches as the UI); only
 * the final encoded file is provider-local, kept in cacheDir/artwork-provider keyed by target
 * hash so a repeat open serves bytes without re-encoding.
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("artwork is read-only")
        val target = uri.getQueryParameter(ARTWORK_URI_PARAM)
            ?.takeIf { isAllowedArtworkTarget(it) }
            ?: throw FileNotFoundException("refused artwork target: $uri")
        val context = requireNotNull(context)
        val dir = File(context.cacheDir, "artwork-provider").apply { mkdirs() }
        val file = File(dir, target.encodeUtf8().sha256().hex() + ".img")
        if (!file.exists()) {
            try {
                encodeTo(file, dir, context, target)
            } catch (e: Exception) {
                throw FileNotFoundException("artwork load failed for $target: $e")
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Binder thread; the app ImageLoader is the async boundary, so blocking here is the contract
     *  (Auto shows its loading placeholder until the descriptor arrives). */
    private fun encodeTo(file: File, dir: File, context: Context, target: String) {
        val app = context.applicationContext as YMusicApplication
        val bitmap = runBlocking {
            app.services.await().imageLoader.load(target, ARTWORK_MAX_PX, ARTWORK_MAX_PX)
        }.asAndroidBitmap()
        val tmp = File.createTempFile(file.name, ".tmp", dir)
        tmp.outputStream().use { out ->
            val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            bitmap.compress(format, 90, out)
        }
        // A lost rename race just means the winner's identical file serves.
        if (!tmp.renameTo(file)) tmp.delete()
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("artwork is read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("artwork is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("artwork is read-only")
}

/** Authority of [ArtworkProvider] for this install (manifest: `${applicationId}.artwork`). */
fun artworkAuthority(context: Context): String = "${context.packageName}.artwork"

/** [url] (an https thumb or `art://` uri) wrapped as a content uri Android Auto can resolve. */
fun artworkContentUri(context: Context, url: String): Uri =
    Uri.Builder()
        .scheme("content")
        .authority(artworkAuthority(context))
        .path("art")
        .appendQueryParameter(ARTWORK_URI_PARAM, url)
        .build()

/** True for targets the exported provider serves: our own `art://pic/<media-id>` uris, or https
 *  images on YouTube's cdns (video thumbs / music covers / channel avatars). Everything else —
 *  arbitrary hosts, other schemes, other apps' content — is refused; an exported provider must
 *  not be an open image proxy. */
internal fun isAllowedArtworkTarget(target: String): Boolean {
    val uri = Uri.parse(target)
    return when (uri.scheme) {
        "art" -> uri.authority == "pic" && uri.lastPathSegment?.toLongOrNull() != null
        "https" -> {
            val host = uri.host ?: return false
            ALLOWED_IMAGE_HOSTS.any { host == it || host.endsWith(".$it") }
        }
        else -> false
    }
}

internal const val ARTWORK_URI_PARAM = "uri"

/** Session/notification/Auto artwork target size; [ymusic.imageloader.ImageLoader] never upscales. */
internal const val ARTWORK_MAX_PX = 640

private val ALLOWED_IMAGE_HOSTS = listOf("ytimg.com", "ggpht.com", "googleusercontent.com")
