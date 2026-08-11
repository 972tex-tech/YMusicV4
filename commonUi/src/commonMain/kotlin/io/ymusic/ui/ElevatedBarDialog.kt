package io.ymusic.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/**
 * The app's standard picker/confirm dialog chrome (first used by the content-region picker): a
 * full-bleed [body] on [DesignColors.Surface], with the title and the button bar sitting on
 * [DesignColors.SurfaceElevated] behind a divider and casting a shadow onto the content when it can
 * scroll under them — the ElevatedBarColumn idiom. The body is bounded to a scrollable region; pass the
 * scroll edges of whatever scrollable it hosts ([androidx.compose.foundation.lazy.LazyListState] or a
 * [androidx.compose.foundation.ScrollState]) as [canScrollBackward]/[canScrollForward] so the shadows
 * follow it. [buttons] fills the bottom bar (right-aligned).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ElevatedBarDialog(
    title: String,
    onDismiss: () -> Unit,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    buttons: @Composable RowScope.() -> Unit,
    body: @Composable () -> Unit,
) {
    // The title/button bars sit a step above the body. On dark, the full SurfaceElevated lift (0x12 →
    // 0x1F) reads too strongly against the near-black body, so use the gentler SurfaceDim step there
    // (the divider + scroll shadow still delineate the bars); light keeps the usual elevated grey.
    val barColor = if (DesignColors.isDark) DesignColors.SurfaceDim else DesignColors.SurfaceElevated
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = DesignColors.Surface,
            modifier = Modifier.widthIn(min = 280.dp, max = 560.dp),
        ) {
            Column {
                val topShadow by animateDpAsState(if (canScrollBackward) 6.dp else 0.dp)
                Column(Modifier.fillMaxWidth().zIndex(1f).shadow(topShadow).background(barColor)) {
                    Text(
                        title,
                        style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                    HorizontalDivider(color = DesignColors.Divider)
                }
                Box(Modifier.weight(1f, fill = false).heightIn(max = 420.dp)) { body() }
                val bottomShadow by animateDpAsState(if (canScrollForward) 6.dp else 0.dp)
                Column(Modifier.fillMaxWidth().zIndex(1f).shadow(bottomShadow).background(barColor)) {
                    HorizontalDivider(color = DesignColors.Divider)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.End,
                        content = buttons,
                    )
                }
            }
        }
    }
}
