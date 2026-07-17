package ymusic.imageloader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException

/**
 * Loads [url] scaled to this box's own measured size and draws it; [placeholder] shows until the
 * bitmap lands (and stays on failure — load errors degrade to the placeholder, logged as a
 * breadcrumb, never thrown into composition). Leaving composition cancels an in-flight load, and
 * because the loader is LIFO, freshly composed items (the ones scrolled into view) load first.
 */
@Composable
fun AsyncImage(
    loader: ImageLoader,
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: (@Composable BoxScope.() -> Unit)? = null,
) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var loaded by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    Box(modifier.onSizeChanged { sizePx = it }) {
        val bitmap = loaded
        if (bitmap != null) {
            Image(
                bitmap,
                contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
            )
        } else {
            placeholder?.invoke(this)
        }
    }
    if (url != null && sizePx != IntSize.Zero) {
        // Keyed on the BUCKETED size: a resize into a new bucket re-decodes (a grown view would
        // otherwise stay blurry forever), while layout jitter within one bucket doesn't relaunch.
        LaunchedEffect(url, bucketPx(sizePx.width), bucketPx(sizePx.height)) {
            loaded = try {
                loader.load(url, sizePx.width, sizePx.height)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                loader.logger?.log(Logger.Level.WARN, listOf("image-loader"), "load failed: $url", e)
                null
            }
        }
    }
}
