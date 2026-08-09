package ymusic.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.runBlocking
import okio.Path
import java.io.FileNotFoundException

/**
 * `content://<pkg>.artwork/art?uri=<encoded target>` — read-only artwork for cross-process
 * consumers that cannot fetch our urls themselves. Android Auto / AAOS resolve queue and browse
 * icons ONLY from local content uris (they never download https, and `art://` is app-internal),
 * so [PlaybackService] publishes every artworkUri in this wrapped form.
 *
 * The provider is exported (Auto holds no permission of ours), so [openFile] trusts nothing: the
 * target must pass [isAllowedArtworkTarget] — our own `art://pic/<id>` or an https image on a
 * YouTube cdn host — anything else 404s. The served file IS the shared app ImageLoader's scaled
 * disk cache entry ([ymusic.imageloader.ImageLoader.loadFile]): no provider-local storage, no
 * transient copy — the loader's journaled LRU owns write atomicity and eviction. A path can be
 * evicted between resolve and open; one re-resolve covers that race.
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("artwork is read-only")
        val target = uri.getQueryParameter(ARTWORK_URI_PARAM)
            ?.takeIf { isAllowedArtworkTarget(it) }
            ?: throw FileNotFoundException("refused artwork target: $uri")
        repeat(2) { attempt ->
            val path = resolve(target)
            try {
                return ParcelFileDescriptor.open(path.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: FileNotFoundException) {
                if (attempt == 1) throw e // evicted twice in a row: give up
            }
        }
        throw FileNotFoundException(target) // unreachable
    }

    /** Binder thread; the app ImageLoader is the async boundary, so blocking here is the contract
     *  (Auto shows its loading placeholder until the descriptor arrives). */
    private fun resolve(target: String): Path {
        val app = requireNotNull(context).applicationContext as YMusicApplication
        try {
            return runBlocking {
                app.services.await().imageLoader.loadFile(target, ARTWORK_MAX_PX, ARTWORK_MAX_PX)
            } ?: throw FileNotFoundException("no scaled disk cache to serve artwork from")
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: Exception) {
            throw FileNotFoundException("artwork load failed for $target: $e")
        }
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
