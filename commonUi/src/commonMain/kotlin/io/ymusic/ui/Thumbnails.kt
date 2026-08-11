package io.ymusic.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import io.ymusic.dynamicengine.yt.YtThumb
import io.ymusic.imageloader.AsyncImage
import io.ymusic.imageloader.ImageLoader

/** Provided at the App root from [AppGraph.imageLoader]; null keeps every thumb a placeholder box. */
val LocalImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

/** Smallest variant that still covers [targetPx] wide, else the largest offered (source order ascends). */
internal fun List<YtThumb>.thumbUrlFor(targetPx: Int): String? =
    firstOrNull { (it.w ?: 0) >= targetPx }?.url ?: lastOrNull()?.url

/**
 * The image layer of a thumbnail box: fills the parent, clipped to [shape], drawn over the
 * placeholder content (which keeps showing until the bitmap lands or when loading fails).
 * [targetDp] picks the variant to download; the decode sizes itself to the measured pixels.
 */
@Composable
internal fun BoxScope.ThumbImage(thumbs: List<YtThumb>, targetDp: Dp, shape: Shape) {
    val targetPx = with(LocalDensity.current) { targetDp.roundToPx() }
    ArtImage(thumbs.thumbUrlFor(targetPx), shape)
}

/** One url straight into the thumbnail box — the single-variant sibling of [ThumbImage]. */
@Composable
internal fun BoxScope.ArtImage(url: String?, shape: Shape) {
    if (url == null) return
    val loader = LocalImageLoader.current ?: return
    AsyncImage(
        loader,
        url,
        contentDescription = null,
        modifier = Modifier.matchParentSize().clip(shape),
        contentScale = ContentScale.Crop,
        // ytimg URLs rotate their signature query params (?sqp=…&rs=…) across sessions; the path
        // alone identifies the image, so keying on it keeps the caches warm across rotations.
        cacheKey = url.substringBefore('?'),
    )
}
