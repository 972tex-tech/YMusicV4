package ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import ymusic.dynamicengine.yt.YtFeedItem
import ymusic.dynamicengine.yt.YtSectionHeader
import ymusic.ui.resources.Res
import ymusic.ui.resources.badge_live
import ymusic.ui.resources.cd_collapse
import ymusic.ui.resources.cd_expand
import ymusic.ui.resources.cd_more

/**
 * The feed building blocks from design/final/feed-components.html: one SectionHeader + the six
 * leaf item components (video/playlist/channel × list/grid). All values mirror the mockup CSS.
 */

internal val ListTitleStyle = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink, lineHeight = 17.5.sp)
internal val ListSubStyle = TextStyle(fontSize = 11.5.sp, color = DesignColors.InkSecondary)
internal val GridTitleStyle = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink, lineHeight = 15.6.sp)
internal val GridSubStyle = TextStyle(fontSize = 11.sp, color = DesignColors.InkSecondary)

/** What the ⋮ overflow offers for an item; empty = no overflow shown. */
class ItemActions(val entries: List<Pair<String, () -> Unit>>)

/**
 * Single-line section header: `[circle icon] · title · [count] · [CTA / collapse toggle]`, any part
 * optional (variants A–D in the mockup).
 */
@Composable
fun SectionHeader(
    header: YtSectionHeader?,
    modifier: Modifier = Modifier,
    collapsed: Boolean? = null,
    onToggle: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (header != null && header.iconThumbs.isNotEmpty()) {
            Box(
                Modifier.size(26.dp).background(DesignColors.PlaceholderDim, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.MusicNote, null, Modifier.size(14.dp), tint = Color(0xFFAAAAAA))
                ThumbImage(header.iconThumbs, 26.dp, CircleShape)
            }
        }
        Text(
            header?.title.orEmpty(),
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = true),
        )
        header?.count?.let {
            Text(it, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = DesignColors.InkFaint))
        }
        when {
            onToggle != null -> ActionIcon(
                Icons.Default.KeyboardArrowDown,
                stringResource(if (collapsed == false) Res.string.cd_collapse else Res.string.cd_expand),
                Modifier.rotate(if (collapsed == false) 180f else 0f),
                iconSize = 20.dp,
                tint = DesignColors.InkSoft,
                onClick = onToggle,
            )
            header?.ctaText != null || header?.ctaToken != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                header.ctaText?.let { Text(it, style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSoft)) }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(14.dp), tint = DesignColors.InkSoft)
            }
        }
    }
}

/** video / playlist / channel — list form. [wide] swaps the square thumb for 16:9 (the
 *  [FeedLayout.WideList] band); [small] is the collapsed-section variant (66dp, 1-line), honored
 *  only in the wide form — the square list has exactly one row size. */
@Composable
fun FeedListItem(
    item: YtFeedItem,
    actions: ItemActions,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    wide: Boolean = false,
) {
    val compact = small && wide
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        when {
            item.kind == YtFeedItem.KIND_CHANNEL -> CircleThumb(item, 74.dp)
            wide -> RectThumb(item, width = if (compact) 117.dp else 160.dp, height = if (compact) 66.dp else 90.dp)
            else -> RectThumb(item, width = 92.dp, height = 92.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = ListTitleStyle,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(item.subtitleLine1, item.subtitleLine2).joinToString(" · ")
            if (!compact && sub.isNotEmpty()) {
                Text(sub, style = ListSubStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
        when {
            item.ctaText != null -> CtaPill(item.ctaText!!)
            actions.entries.isNotEmpty() -> MoreMenu(actions, Modifier.align(Alignment.Top).padding(top = 2.dp))
        }
    }
}

/** video / playlist — grid card: 16:9 thumb + title/sub row with the ⋮ beside the title. */
@Composable
fun FeedGridCard(
    item: YtFeedItem,
    actions: ItemActions,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    inset: Dp = GRID_CARD_INSET,
) {
    if (item.kind == YtFeedItem.KIND_CHANNEL) {
        ChannelGridCard(item, onClick, modifier, inset)
        return
    }
    // clip → clickable → padding: the hover/ripple gets rounded corners and a breathing inset
    // around the content (grid callers shave the inset off their gaps to keep the visual metrics).
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(inset),
    ) {
        // Content is inset on all four sides (top==bottom, left==right). The
        // playlist stack tab draws OVERLAPPING into the top inset (the mockup's `top:-5px` overlay)
        // — no reserved strip, so every card's thumb sits at the same offset.
        Box(Modifier.fillMaxWidth()) {
            if (item.kind == YtFeedItem.KIND_PLAYLIST) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp)
                        .offset(y = (-4).dp)
                        .height(4.dp)
                        .background(DesignColors.StackTab, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(DesignColors.Placeholder, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Image, null, Modifier.size(22.dp), tint = DesignColors.PlaceholderIcon)
                ThumbImage(item.thumbs, GRID_THUMB_TARGET, RoundedCornerShape(7.dp))
                Badges(item, Modifier.align(Alignment.BottomEnd).padding(4.dp), textSize = 10, iconSize = 11.dp)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = GridTitleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(item.subtitleLine1, item.subtitleLine2).joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(sub, style = GridSubStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (actions.entries.isNotEmpty()) MoreMenu(actions, Modifier.padding(top = 1.dp))
        }
    }
}

/** channel — grid card: centered circle avatar + name + subtitle + CTA pill. */
@Composable
private fun ChannelGridCard(
    item: YtFeedItem,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    inset: Dp = GRID_CARD_INSET,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(inset),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircleThumb(item, 76.dp)
        Text(
            item.title,
            style = GridTitleStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        item.subtitleLine1?.let {
            Text(it, style = GridSubStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        item.ctaText?.let { Box(Modifier.padding(top = 8.dp)) { CtaPill(it) } }
    }
}

/** The bordered "Show more (N)" / "Show less" pill under a collapsible section. */
@Composable
fun ShowMoreButton(label: String, expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, DesignColors.OutlineSoft, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.InkMuted))
        Icon(
            Icons.Default.KeyboardArrowDown,
            null,
            Modifier.size(15.dp).rotate(if (expanded) 180f else 0f),
            tint = DesignColors.InkMuted,
        )
    }
}

/** The outlined pill CTA on channel items ("Subscribe"). Action lands with accounts. */
@Composable
internal fun CtaPill(text: String) {
    Text(
        text,
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
        modifier = Modifier
            .border(1.4.dp, DesignColors.Ink, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun MoreMenu(actions: ItemActions, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        ActionIcon(
            Icons.Default.MoreVert,
            stringResource(Res.string.cd_more),
            tint = DesignColors.IconDots,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.entries.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { action(); open = false })
            }
        }
    }
}

@Composable
private fun CircleThumb(item: YtFeedItem, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).background(DesignColors.Placeholder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Person, null, Modifier.size(28.dp), tint = DesignColors.PlaceholderIcon)
        ThumbImage(item.thumbs, size, CircleShape)
    }
}

/** Square/16:9 list thumbnail; playlists get the stacked-cards shadow behind it. */
@Composable
private fun RectThumb(item: YtFeedItem, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
    val stacked = item.kind == YtFeedItem.KIND_PLAYLIST
    Box(
        Modifier
            .size(width = width, height = height)
            .then(if (stacked) Modifier.drawBehind { drawPlaylistStack() } else Modifier)
            .background(DesignColors.Placeholder, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Image, null, Modifier.size(if (height < 80.dp) 21.dp else 30.dp), tint = DesignColors.PlaceholderIcon)
        ThumbImage(item.thumbs, width, RoundedCornerShape(8.dp))
        Badges(item, Modifier.align(Alignment.BottomEnd).padding(4.dp), textSize = 9, iconSize = 10.dp)
    }
}

/** Grid cells vary with screen width; this is a sane middle target for picking the variant. */
private val GRID_THUMB_TARGET = 220.dp

/** The card width grids aim for — the column count is however many of these fit. */
val GRID_CARD_TARGET = 240.dp

/** The hover/ripple's breathing room inside a grid card; grid gaps subtract it (2× between cards). */
val GRID_CARD_INSET = 8.dp

/** Duration / item-count / LIVE badge in the thumbnail's bottom-right corner. */
@Composable
internal fun Badges(item: YtFeedItem, modifier: Modifier, textSize: Int, iconSize: androidx.compose.ui.unit.Dp) {
    val badgeText = TextStyle(fontSize = textSize.sp, color = Color.White)
    when {
        item.live -> Text(
            stringResource(Res.string.badge_live),
            style = badgeText.copy(fontWeight = FontWeight.Bold),
            modifier = modifier.background(Color(0xFFCC0000), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
        )
        item.itemCount != null -> Row(
            modifier.background(DesignColors.BadgeScrim, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, Modifier.size(iconSize), tint = Color.White)
            Text(item.itemCount!!, style = badgeText)
        }
        item.duration != null -> Text(
            item.duration!!,
            style = badgeText,
            modifier = modifier.background(DesignColors.BadgeInk, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlaylistStack() {
    // The two card edges peeking up-right behind a playlist thumb (mockup box-shadow offsets).
    val r = CornerRadius(8.dp.toPx())
    fun layer(offsetPx: Float, shrinkPx: Float, color: Color) {
        drawRoundRect(
            color = color,
            topLeft = Offset(offsetPx + shrinkPx, -offsetPx + shrinkPx),
            size = Size(size.width - 2 * shrinkPx, size.height - 2 * shrinkPx),
            cornerRadius = r,
        )
    }
    layer(8.dp.toPx(), 2.dp.toPx(), DesignColors.StackFar)
    layer(4.dp.toPx(), 1.dp.toPx(), DesignColors.StackNear)
}
