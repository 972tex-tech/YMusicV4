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
import kotlinx.coroutines.delay

/**
 * Loads [url] scaled to this box's own measured size and draws it. [placeholder] shows while
 * loading; [error] (falling back to [placeholder]) shows when the load failed — failures degrade
 * to the slot, logged as a breadcrumb, never thrown into composition. Leaving composition cancels
 * an in-flight load, and because the loader is LIFO, freshly composed items (the ones scrolled
 * into view) load first.
 *
 * A resize does NOT naively reload: once a bitmap is showing, only growth of at least
 * [RELOAD_GROWTH_PX] in a dimension warrants a sharper decode (shrinking just downscales), and the
 * reload waits [RESIZE_SETTLE_MS] for the resize to settle — every further size change restarts
 * the wait, so a live window drag triggers one reload at the end, not a stream of them.
 */
@Composable
fun AsyncImage(
    loader: ImageLoader,
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: (@Composable BoxScope.() -> Unit)? = null,
    error: (@Composable BoxScope.() -> Unit)? = null,
) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var loaded by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    // The size the showing bitmap was decoded for — the growth-threshold baseline.
    var loadedFor by remember(url) { mutableStateOf(IntSize.Zero) }

    Box(modifier.onSizeChanged { sizePx = it }) {
        // Synchronous memory-cache peek: an already-cached image draws on its FIRST frame — routing
        // the hit through the effect would flash the placeholder for a frame (list scroll jitter).
        val bitmap = loaded
            ?: if (url != null && sizePx != IntSize.Zero) loader.peek(url, sizePx.width, sizePx.height) else null
        when {
            bitmap != null -> Image(
                bitmap,
                contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
            )
            failed -> (error ?: placeholder)?.invoke(this)
            else -> placeholder?.invoke(this)
        }
    }

    if (url != null && sizePx != IntSize.Zero) {
        LaunchedEffect(url, sizePx) {
            if (loaded != null) {
                val growsW = sizePx.width - loadedFor.width >= RELOAD_GROWTH_PX
                val growsH = sizePx.height - loadedFor.height >= RELOAD_GROWTH_PX
                if (!growsW && !growsH) return@LaunchedEffect
                delay(RESIZE_SETTLE_MS)
            }
            loadedFor = sizePx
            try {
                loaded = loader.load(url, sizePx.width, sizePx.height)
                failed = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                loader.logger?.log(Logger.Level.WARN, listOf("image-loader"), "load failed: $url", e)
                // A failed sharper re-decode keeps the bitmap already showing.
                failed = loaded == null
            }
        }
    }
}

private const val RELOAD_GROWTH_PX = 100
private const val RESIZE_SETTLE_MS = 400L
