package io.ymusic.desktop.setup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The two third-party marks the first run names: VLC and GitHub.
 *
 * Both are drawn from the SVG path data of the design mockup rather than redrawn by hand, so the
 * shapes are the ones that were approved. They are tinted with `currentColor` in the design and
 * take a [color] here for the same reason — the VLC cone has to read as a warning next to a red
 * failure and as nothing much once VLC is found.
 */
private object IconPaths {
    /**
     * 24×24 traffic cone: tip, body, base bar.
     *
     * Drawn solid rather than with the mockup's faded tip — at 15dp the translucent band merged into
     * the body and the whole thing read as a lump instead of a cone. The silhouette is what carries
     * the recognition at this size, so it gets one flat colour and a gap between cone and base.
     */
    const val VLC_TIP = "M10.6 3h2.8l.9 3h-4.6l.9-3z"
    const val VLC_BODY = "M9.4 7.4h5.2l3.1 8.6H6.3l3.1-8.6z"
    const val VLC_BASE = "M6 16.4h12a1 1 0 0 1 1 1v0.8a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-0.8a1 1 0 0 1 1-1z"

    /** 24×24, drawn as a stroke: the two segments of a tick, centred in the viewport. */
    const val CHECK = "M5.5 12.5L10 17L18.5 7.5"

    /** 24×24, two strokes crossing — the failure mark. */
    const val CROSS_A = "M6.5 6.5L17.5 17.5"
    const val CROSS_B = "M17.5 6.5L6.5 17.5"

    /** 24×24 five-point star — the action on the GitHub row, not the identity of it. */
    const val STAR = "M12 2.6l2.85 5.78 6.38.93-4.62 4.5 1.09 6.35L12 17.16l-5.7 3l1.09-6.35-4.62-4.5 " +
        "6.38-.93z"

    /** 24×24 GitHub mark (the Octocat silhouette), one path. */
    const val GITHUB = "M12 .5C5.73.5.5 5.73.5 12c0 5.08 3.29 9.39 7.86 10.91.58.11.79-.25.79-.56 " +
        "0-.28-.01-1.02-.02-2-3.2.7-3.88-1.54-3.88-1.54-.53-1.34-1.29-1.7-1.29-1.7-1.05-.72.08-.71.08-.71 " +
        "1.16.08 1.77 1.19 1.77 1.19 1.03 1.77 2.7 1.26 3.36.96.1-.75.4-1.26.73-1.55-2.55-.29-5.23-1.28-5.23-5.7 " +
        "0-1.26.45-2.29 1.19-3.1-.12-.29-.52-1.46.11-3.05 0 0 .97-.31 3.18 1.18a11 11 0 0 1 5.8 0c2.2-1.49 " +
        "3.17-1.18 3.17-1.18.63 1.59.23 2.76.11 3.05.74.81 1.19 1.84 1.19 3.1 0 4.43-2.69 5.41-5.25 " +
        "5.69.41.36.78 1.06.78 2.14 0 1.55-.01 2.8-.01 3.18 0 .31.21.68.8.56A11.51 11.51 0 0 0 23.5 " +
        "12C23.5 5.73 18.27.5 12 .5z"
}

/** Paints [pathData] (24×24 user units) scaled to fill the canvas. */
private fun DrawScope.drawIconPath(pathData: String, color: Color, alpha: Float = 1f) {
    val factor = size.minDimension / ICON_VIEWPORT
    scale(factor, factor, pivot = androidx.compose.ui.geometry.Offset.Zero) {
        drawPath(PathParser().parsePathString(pathData).toPath(), color, alpha = alpha, style = Fill)
    }
}

/**
 * The VLC traffic cone, in VLC's own orange rather than the row's warning tint: it is a product
 * mark, and the point of showing it is that someone recognises the thing they need to install.
 *
 * Unlike the other marks this one maps its OWN bounds to the canvas. The cone occupies about
 * two-thirds of the 24-unit viewport, so drawing it like the square marks made a 20dp request come
 * out as a 13dp cone — [size] here is the size of the mark, not of the box around it.
 */
@Composable
fun VlcIcon(color: Color = VlcOrange, size: Dp = VLC_SIZE) {
    Canvas(Modifier.size(size)) {
        val factor = this.size.height / CONE_HEIGHT
        val centring = (this.size.width / factor - CONE_WIDTH) / 2 - CONE_LEFT
        scale(factor, factor, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            translate(centring, -CONE_TOP) {
                listOf(IconPaths.VLC_TIP, IconPaths.VLC_BODY, IconPaths.VLC_BASE).forEach {
                    drawPath(PathParser().parsePathString(it).toPath(), color, style = Fill)
                }
            }
        }
    }
}

/** The cone's bounding box inside the 24-unit viewport. */
private const val CONE_LEFT = 5f
private const val CONE_TOP = 3f
private const val CONE_WIDTH = 14f
private const val CONE_HEIGHT = 16.2f

/** VideoLAN's cone orange. */
val VlcOrange = Color(0xFFE85E00)

@Composable
fun GitHubIcon(color: Color, size: Dp = ICON_SIZE) {
    Canvas(Modifier.size(size)) { drawIconPath(IconPaths.GITHUB, color) }
}

/**
 * The tick inside the consent box, drawn rather than typed.
 *
 * A "✓" character centres its LINE BOX, not its glyph — the font's ascent and descent are not
 * symmetric about the mark, so the tick sat visibly low in a 16dp box. A stroked path has no
 * metrics to fight and lands where it is put.
 */
@Composable
fun CheckMark(color: Color, size: Dp = CHECK_SIZE) {
    Canvas(Modifier.size(size)) {
        val factor = this.size.minDimension / ICON_VIEWPORT
        val path = PathParser().parsePathString(IconPaths.CHECK).toPath()
        scale(factor, factor, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(
                path,
                color,
                style = Stroke(
                    width = CHECK_STROKE,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

/** The state marks on the checklist rows. Drawn, not typed, for the same reason as [CheckMark]:
 *  a character centres its line box and a shape centres itself, and the two do not agree. */
@Composable
fun PendingCircle(color: Color, size: Dp = STATUS_SIZE) {
    Canvas(Modifier.size(size)) {
        drawCircle(color, radius = (this.size.minDimension - STATUS_STROKE_PX) / 2, style = Stroke(STATUS_STROKE_PX))
    }
}

/** A half-filled disc — the typographic ◐, which is what the design uses for work in progress. */
@Composable
fun BusyCircle(color: Color, size: Dp = STATUS_SIZE) {
    Canvas(Modifier.size(size)) {
        val diameter = this.size.minDimension - STATUS_STROKE_PX
        val offset = androidx.compose.ui.geometry.Offset(STATUS_STROKE_PX / 2, STATUS_STROKE_PX / 2)
        val box = androidx.compose.ui.geometry.Size(diameter, diameter)
        drawCircle(color, radius = diameter / 2, style = Stroke(STATUS_STROKE_PX))
        drawArc(color, startAngle = 90f, sweepAngle = 180f, useCenter = true, topLeft = offset, size = box)
    }
}

@Composable
fun CrossMark(color: Color, size: Dp = STATUS_SIZE) {
    Canvas(Modifier.size(size)) {
        val factor = this.size.minDimension / ICON_VIEWPORT
        scale(factor, factor, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            listOf(IconPaths.CROSS_A, IconPaths.CROSS_B).forEach {
                drawPath(
                    PathParser().parsePathString(it).toPath(),
                    color,
                    style = Stroke(width = CHECK_STROKE, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
fun StarIcon(color: Color, size: Dp = BUTTON_ICON_SIZE) {
    Canvas(Modifier.size(size)) { drawIconPath(IconPaths.STAR, color) }
}

private const val ICON_VIEWPORT = 24f

/** The drawn height of the cone itself. */
private val VLC_SIZE = 20.dp

/** Sized to sit in the rows' glyph column beside 13sp text glyphs. */
private val ICON_SIZE = 15.dp

/** Inside a button, next to its 12sp label. */
private val BUTTON_ICON_SIZE = 12.dp

/** Inside the consent tick box, which is 16dp. */
private val CHECK_SIZE = 12.dp

/** A row's leading state mark, matched to the 13sp glyphs it replaces. */
private val STATUS_SIZE = 13.dp

/** In pixels at draw time — these shapes are built from primitives, not the 24-unit viewport. */
private const val STATUS_STROKE_PX = 1.6f

/** In 24-unit viewport terms, so it scales with the icon. */
private const val CHECK_STROKE = 2.8f
