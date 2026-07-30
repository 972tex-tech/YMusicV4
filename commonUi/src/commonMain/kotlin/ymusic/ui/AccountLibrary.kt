package ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import ymusic.dynamicengine.yt.YtAccountInfo
import ymusic.dynamicengine.yt.YtFeedItem
import ymusic.ui.resources.Res
import ymusic.ui.resources.account_library_empty
import ymusic.ui.resources.account_liked_videos
import ymusic.ui.resources.account_my_channel
import ymusic.ui.resources.account_sign_out
import ymusic.ui.resources.account_watch_later
import ymusic.ui.resources.cd_account_avatar

/**
 * The signed-in Discover "Library" tab (design/final/library.html): the account header (avatar ·
 * name · email · overflow), the account nav — rows in portrait, chips on a wide window — and the
 * engine's library feed sections (History / Playlists / Watch later / Liked videos) below, sharing
 * the standard [FeedList] list/grid responsiveness.
 */
@Composable
internal fun AccountLibraryScreen(
    session: FeedSession,
    coordinator: PlaybackCoordinator,
    account: YtAccountInfo?,
    onSignOut: () -> Unit,
    onOpen: ((YtFeedItem) -> Unit)?,
    onDeeplink: ((link: String, title: String?) -> Unit)?,
) {
    FeedList(
        session.pager,
        coordinator,
        emptyMessage = stringResource(Res.string.account_library_empty),
        onOpen = onOpen,
        listState = session.listState,
        onDeeplink = onDeeplink,
        topContent = { AccountLibraryHeader(account, onSignOut, onDeeplink) },
    )
}

@Composable
private fun AccountLibraryHeader(
    account: YtAccountInfo?,
    onSignOut: () -> Unit,
    onDeeplink: ((link: String, title: String?) -> Unit)?,
) {
    Column {
        AccountCard(account, onSignOut)
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = DesignColors.Divider)
        // The nav targets are the app's OWN links in the one scheme it parses (Deeplink.kt): the
        // account's channel, and YouTube's eternal Watch-later/Liked pseudo-playlists. These three
        // are fixed chrome (icons + localized labels), not engine content — a deliberate exception
        // to "urls come from the engine": if WL/LL ever rot, no engine-composed url would save
        // rows the app draws itself.
        val name = account?.name
        val navRows = buildList {
            account?.channelId?.let {
                add(NavTarget(Icons.Outlined.AccountCircle, stringResource(Res.string.account_my_channel), "/channel/$it", name))
            }
            add(NavTarget(Icons.Outlined.Schedule, stringResource(Res.string.account_watch_later), "/playlist?list=WL", null))
            add(NavTarget(Icons.Outlined.ThumbUp, stringResource(Res.string.account_liked_videos), "/playlist?list=LL", null))
        }
        BoxWithConstraints {
            if (maxWidth >= 600.dp) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    navRows.forEach { t -> AccountNavChip(t, onDeeplink) }
                }
            } else {
                Column(Modifier.padding(vertical = 4.dp)) {
                    navRows.forEach { t -> AccountNavRow(t, onDeeplink) }
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = DesignColors.Divider)
    }
}

private class NavTarget(
    val icon: ImageVector,
    val label: String,
    val url: String,
    /** Seeds the opened page's header while it loads (the account name for My channel). */
    val seedTitle: String?,
)

@Composable
private fun AccountCard(account: YtAccountInfo?, onSignOut: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 15.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier.size(46.dp).background(DesignColors.Ink, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val thumbs = account?.thumbs ?: emptyList()
            val initial = account?.name?.firstOrNull()?.uppercaseChar()
            when {
                thumbs.isNotEmpty() -> ThumbImage(thumbs, 46.dp, CircleShape)
                initial != null -> Text(
                    initial.toString(),
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = DesignColors.OnInk),
                )
                else -> Icon(
                    Icons.Filled.Person,
                    stringResource(Res.string.cd_account_avatar),
                    tint = DesignColors.OnInk,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                account?.name.orEmpty(),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            (account?.email ?: account?.handle)?.let { line ->
                Text(
                    line,
                    style = TextStyle(fontSize = 12.sp, color = DesignColors.InkMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        MoreMenu(ItemActions(listOf(stringResource(Res.string.account_sign_out) to onSignOut)))
    }
}

@Composable
private fun AccountNavRow(target: NavTarget, onDeeplink: ((String, String?) -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onDeeplink?.invoke(target.url, target.seedTitle ?: target.label) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(target.icon, null, Modifier.size(21.dp), tint = DesignColors.InkMuted)
        Text(target.label, style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink))
    }
}

@Composable
private fun AccountNavChip(target: NavTarget, onDeeplink: ((String, String?) -> Unit)?) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .border(1.dp, DesignColors.OutlineSoft, shape)
            .clip(shape)
            .clickable { onDeeplink?.invoke(target.url, target.seedTitle ?: target.label) }
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(target.icon, null, Modifier.size(16.dp), tint = DesignColors.InkMuted)
        Text(target.label, style = TextStyle(fontSize = 12.5.sp, color = DesignColors.Ink), maxLines = 1)
    }
}
