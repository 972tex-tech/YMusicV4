package ymusic.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ymusic.imageloader.ImageLoader

/**
 * media3 [BitmapLoader] over the shared app [ImageLoader]: session + notification artwork resolves
 * through the same fetchers as the UI (https thumbs, `art://` ffmpeg extraction) and shares its
 * memory LRU and scaled disk cache — a thumb the UI already shows never refetches or re-decodes.
 *
 * [ArtworkProvider]-minted `content://` uris (what [MediaMetadata.artworkUri] carries so Android
 * Auto can resolve queue icons) are unwrapped back to their target url IN process — same
 * [ImageLoader] cache key as the raw url, no binder round-trip through the provider.
 */
class ImageLoaderBitmapLoader(
    private val imageLoader: ImageLoader,
    private val scope: CoroutineScope,
    private val ownAuthority: String,
) : BitmapLoader {

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = future {
        BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: throw IllegalArgumentException("could not decode image data")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = future {
        val target = if (uri.scheme == "content" && uri.authority == ownAuthority) {
            uri.getQueryParameter(ARTWORK_URI_PARAM)
                ?: throw IllegalArgumentException("artwork content uri without a target: $uri")
        } else {
            uri.toString()
        }
        imageLoader.load(target, ARTWORK_MAX_PX, ARTWORK_MAX_PX).asAndroidBitmap()
    }

    private fun <T : Any> future(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        val job = scope.launch(Dispatchers.Default) {
            try {
                future.set(block())
            } catch (e: CancellationException) {
                future.cancel(false)
            } catch (e: Throwable) {
                future.setException(e)
            }
        }
        future.addListener({ if (future.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return future
    }
}
