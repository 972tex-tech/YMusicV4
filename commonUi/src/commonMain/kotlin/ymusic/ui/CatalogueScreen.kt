package ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ymusic.dynamicengine.yt.YtFeedItem
import ymusic.dynamicengine.yt.YtSectionHeader
import ymusic.dynamicengine.yt.YtThumb

/**
 * Development-only component catalogue (a Storybook, not a product screen): every shared UI
 * component rendered on mock data, for design iteration and feedback. Reached from the drawer
 * ("UI catalogue") or standalone via `./gradlew :desktopApp:catalogue` — the standalone window
 * skips the whole engine/CEF boot, so the design loop is seconds.
 */
@Composable
fun CatalogueScreen(modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize()) {
        item { CatLabel("SectionHeader — title · icon+count · CTA · collapsible") }
        item { SectionHeader(YtSectionHeader(title = "Recommended")) }
        item { SectionHeader(YtSectionHeader(title = "Sơn Tùng M-TP", iconThumbs = mockAvatarThumbs, count = "500+")) }
        item { SectionHeader(YtSectionHeader(title = "Trending", count = "50", ctaText = "View all", ctaToken = "t")) }
        item {
            var collapsed by remember { mutableStateOf(false) }
            SectionHeader(YtSectionHeader(title = "Collapsible"), collapsed = collapsed, onToggle = { collapsed = !collapsed })
        }

        item { CatLabel("FeedListItem — video · live · playlist · channel · small") }
        item { FeedListItem(mockVideo, mockActions, onClick = {}) }
        item { FeedListItem(mockLive, mockActions, onClick = {}) }
        item { FeedListItem(mockPlaylist, mockActions, onClick = {}) }
        item { FeedListItem(mockChannel, ItemActions(emptyList()), onClick = {}) }
        item { FeedListItem(mockVideo, mockActions, onClick = {}, small = true) }

        item { CatLabel("FeedGridCard — video · playlist · channel") }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(15.dp),
            ) {
                FeedGridCard(mockVideo, mockActions, onClick = {}, modifier = Modifier.weight(1f))
                FeedGridCard(mockPlaylist, mockActions, onClick = {}, modifier = Modifier.weight(1f))
                FeedGridCard(mockChannel, ItemActions(emptyList()), onClick = {}, modifier = Modifier.weight(1f))
            }
        }

        item { CatLabel("ShowMoreButton") }
        item {
            var expanded by remember { mutableStateOf(false) }
            ShowMoreButton(
                if (expanded) "Show less" else "Show more (12)",
                expanded = expanded,
                onClick = { expanded = !expanded },
            )
        }

        item { CatLabel("Thumbnails — live loader (needs network; placeholder without)") }
        item { FeedListItem(mockVideo.copy(thumbs = mockVideoThumbs), mockActions, onClick = {}) }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(15.dp),
            ) {
                FeedGridCard(mockVideo.copy(thumbs = mockVideoThumbs), mockActions, onClick = {}, modifier = Modifier.weight(1f))
                FeedGridCard(mockChannel.copy(thumbs = mockAvatarThumbs), ItemActions(emptyList()), onClick = {}, modifier = Modifier.weight(1f))
            }
        }

        item { CatLabel("AppDrawer") }
        item {
            var selected by remember { mutableStateOf(AppSection.Discover) }
            Box(
                Modifier
                    .padding(horizontal = 16.dp)
                    .height(560.dp)
                    .border(1.dp, DesignColors.Outline)
                    .clipToBounds(),
            ) {
                AppDrawer(
                    section = selected,
                    appVersion = "catalogue · mock",
                    onSelect = { selected = it },
                )
            }
        }

        item { CatLabel("DesignColors") }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                colorSwatches.forEach { (name, color) -> ColorSwatch(name, color) }
            }
        }

        item { CatLabel("Key spacings (dp)") }
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                spacingTokens.forEach { (value, label) -> SpacingBar(value, label) }
            }
        }

        item { CatLabel("Corner radii (dp)") }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                radiusTokens.forEach { (value, label) -> RadiusBox(value, label) }
            }
        }

        item { Box(Modifier.height(40.dp)) }
    }
}

/** A bar exactly [value] dp wide — the spacing at true size, next to its name. */
@Composable
private fun SpacingBar(value: Int, label: String) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Box(
            Modifier
                .width(value.dp)
                .height(14.dp)
                .background(DesignColors.Accent.copy(alpha = 0.4f)),
        )
        Text(
            "  $value · $label",
            style = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = DesignColors.InkSoft),
            maxLines = 1,
        )
    }
}

@Composable
private fun RadiusBox(value: Int, label: String) {
    val shape = RoundedCornerShape(if (value == PILL_RADIUS) 999.dp else value.dp)
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 64.dp, height = 40.dp)
                .background(DesignColors.Placeholder, shape)
                .border(1.dp, DesignColors.Outline, shape),
        )
        Text(
            if (value == PILL_RADIUS) "pill · $label" else "$value · $label",
            style = TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = DesignColors.InkSoft),
            modifier = Modifier.padding(top = 3.dp),
            maxLines = 1,
        )
    }
}

/** Dev chrome: monospace caption + divider above each catalogue group. */
@Composable
private fun CatLabel(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
        Text(
            text,
            style = TextStyle(
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = DesignColors.InkSecondary,
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        HorizontalDivider(color = DesignColors.Divider)
    }
}

@Composable
private fun ColorSwatch(name: String, color: Color) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 64.dp, height = 40.dp)
                .background(color, RoundedCornerShape(6.dp))
                .border(1.dp, DesignColors.Divider, RoundedCornerShape(6.dp)),
        )
        Text(
            name,
            style = TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = DesignColors.InkSoft),
            modifier = Modifier.padding(top = 3.dp).width(64.dp),
            maxLines = 1,
        )
    }
}

// ---- mock data (titles from the test fixtures; thumbs are real CDN URLs for the loader demo) ----

private val mockVideoThumbs = listOf(
    YtThumb("https://i.ytimg.com/vi/FN7ALfpGxiI/mqdefault.jpg", 320, 180),
    YtThumb("https://i.ytimg.com/vi/FN7ALfpGxiI/hqdefault.jpg", 480, 360),
    YtThumb("https://i.ytimg.com/vi/FN7ALfpGxiI/hq720.jpg", 1280, 720),
)

private val mockAvatarThumbs = listOf(
    YtThumb(
        "https://yt3.googleusercontent.com/ru3SakOhxGBHZnrVeKPwmMndF81j1-gIrsPtmGSHV1YbX3Y6JoFVMR93UdlfpKp9_xIeh62t=s176-c-k-c0x00ffffff-no-rj-mo",
        176,
        176,
    ),
)

private val mockVideo = YtFeedItem(
    kind = YtFeedItem.KIND_VIDEO,
    id = "FN7ALfpGxiI",
    title = "NƠI NÀY CÓ ANH | OFFICIAL MUSIC VIDEO | SƠN TÙNG M-TP",
    subtitleLine1 = "Sơn Tùng M-TP Official",
    subtitleLine2 = "453,141,392 views · 9 years ago",
    channelId = "UClyA28-01x4z60eWQ2kiNbA",
    duration = "4:39",
)

private val mockLive = YtFeedItem(
    kind = YtFeedItem.KIND_VIDEO,
    id = "live1",
    title = "lofi hip hop radio — beats to relax/study to",
    subtitleLine1 = "Lofi Girl",
    subtitleLine2 = "32K watching",
    live = true,
)

private val mockPlaylist = YtFeedItem(
    kind = YtFeedItem.KIND_PLAYLIST,
    id = "RDEMeyKnYmm7ScRhnRO9nwOSNA",
    title = "Mix — Sơn Tùng M-TP",
    subtitleLine1 = "Sơn Tùng M-TP, JustaTee, Karik, and more",
    itemCount = "42",
)

private val mockChannel = YtFeedItem(
    kind = YtFeedItem.KIND_CHANNEL,
    id = "UClyA28-01x4z60eWQ2kiNbA",
    title = "Sơn Tùng M-TP Official",
    subtitleLine1 = "11.8M subscribers",
    ctaText = "Subscribe",
    channelId = "UClyA28-01x4z60eWQ2kiNbA",
)

private val mockActions = ItemActions(
    listOf(
        "Play next" to {},
        "Add to queue" to {},
        "Download" to {},
    ),
)

// The recurring dp values of the design language (design/final CSS), largest first.
private val spacingTokens: List<Pair<Int, String>> = listOf(
    16 to "screen edge padding",
    15 to "grid gap",
    14 to "section header top",
    12 to "list row gap (thumb - text)",
    9 to "header items gap",
    8 to "chip gap",
    7 to "list row vertical / header bottom",
    3 to "title - subtitle gap",
)

private const val PILL_RADIUS = -1

private val radiusTokens: List<Pair<Int, String>> = listOf(
    13 to "logo tile",
    10 to "list thumb",
    9 to "grid thumb",
    8 to "mini art",
    3 to "badge",
    PILL_RADIUS to "chips/CTA",
)

private val colorSwatches: List<Pair<String, Color>> = listOf(
    "Accent" to DesignColors.Accent,
    "Ink" to DesignColors.Ink,
    "InkMuted" to DesignColors.InkMuted,
    "InkSoft" to DesignColors.InkSoft,
    "InkSecondary" to DesignColors.InkSecondary,
    "InkFaint" to DesignColors.InkFaint,
    "Placeholder" to DesignColors.Placeholder,
    "PlaceholderDim" to DesignColors.PlaceholderDim,
    "PlaceholderIcon" to DesignColors.PlaceholderIcon,
    "Outline" to DesignColors.Outline,
    "OutlineSoft" to DesignColors.OutlineSoft,
    "Divider" to DesignColors.Divider,
    "SurfaceDim" to DesignColors.SurfaceDim,
    "BadgeScrim" to DesignColors.BadgeScrim,
    "BrandGreen" to DesignColors.BrandGreen,
)
