package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.download.DownloadFormat
import io.ymusic.download.DownloadKind
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtLocaleOption
import io.ymusic.dynamicengine.yt.ytLocaleOptions
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_back
import io.ymusic.ui.resources.cd_more
import io.ymusic.ui.resources.download_audio_language
import io.ymusic.ui.resources.download_audio_only
import io.ymusic.ui.resources.download_captions
import io.ymusic.ui.resources.download_captions_off
import io.ymusic.ui.resources.download_default_track
import io.ymusic.ui.resources.download_format
import io.ymusic.ui.resources.download_quality
import io.ymusic.ui.resources.download_video
import io.ymusic.ui.resources.playlist_added
import io.ymusic.ui.resources.playlist_check_all
import io.ymusic.ui.resources.playlist_check_not_downloaded
import io.ymusic.ui.resources.playlist_download_action
import io.ymusic.ui.resources.playlist_download_title
import io.ymusic.ui.resources.playlist_load_more
import io.ymusic.ui.resources.playlist_options
import io.ymusic.ui.resources.playlist_uncheck_all
import io.ymusic.ui.resources.playlist_uncheck_downloaded
import io.ymusic.ui.resources.playlist_videos_loaded
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Video heights a playlist download can ask for; each video takes its nearest available. */
private val PLAYLIST_HEIGHTS = listOf(2160, 1440, 1080, 720, 480, 360)

/**
 * A playlist download page's retained state. It shares the playlist page's [pager], so the videos
 * already loaded there are listed at once and "Load more" extends both. [preset] holds the picks every
 * checked video is downloaded with — languages, not tracks, resolved per video like Quick download.
 */
@Stable
class PlaylistDownloadSession(val title: String, val pager: Pager, initialPreset: QuickDownloadPreset?) {
    var preset by mutableStateOf(initialPreset ?: QuickDownloadPreset(DownloadFormat.M4A, 1080, null, null))
    var checked by mutableStateOf<Set<String>>(emptySet())

    /** Every video id listed so far: a newly loaded page arrives checked, a seen one keeps its box. */
    private val seen = mutableSetOf<String>()

    internal fun onLoaded(videos: List<YtFeedItem>) {
        val fresh = videos.filter { seen.add(it.id) }
        if (fresh.isNotEmpty()) checked = checked + fresh.filter { !it.live }.map { it.id }
    }
}

@Composable
fun PlaylistDownloadScreen(graph: AppGraph, session: PlaylistDownloadSession, onBack: () -> Unit) {
    val downloads = graph.downloads ?: return
    val state by session.pager.state.collectAsState()
    val ready = state as? Pager.State.Ready
    val videos = ready?.sections.orEmpty().flatMap { it.items }.filter { it.kind == YtFeedItem.KIND_VIDEO }.distinctBy { it.id }
    LaunchedEffect(videos) { session.onLoaded(videos) }
    val offline = LocalAvailableOffline.current
    val languages = rememberYtLanguages(graph)
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val count = videos.count { it.id in session.checked && !it.live }

    Column(Modifier.fillMaxSize().background(DesignColors.Surface)) {
        ElevatedBarColumn {
            AppBarRow {
                ActionIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.cd_back), onClick = onBack)
                AppBarTitle(stringResource(Res.string.playlist_download_title))
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                LoadedHeader(session, ready, videos, offline)
            }
            item {
                OptionsCard(session, languages, ffmpegAvailable = downloads.ffmpegAvailable)
            }
            items(videos, key = { it.id }) { video ->
                VideoCheckRow(video, checked = video.id in session.checked, downloaded = video.id in offline) { on ->
                    session.checked = if (on) session.checked + video.id else session.checked - video.id
                }
            }
        }
        HorizontalDivider(color = DesignColors.Divider)
        Box(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(DesignSpacing.Space16),
            contentAlignment = Alignment.Center,
        ) {
            Button(
                onClick = {
                    val picked = videos.filter { it.id in session.checked && !it.live }
                    val preset = session.preset
                    scope.launch {
                        val gated = graph.mediaStore != null && graph.libraryFolderPicker != null
                        if (gated && !hasUsableDownloadLocation(graph) && !pickDownloadFolder(graph)) return@launch
                        picked.forEach { downloads.enqueue(preset.request(it)) }
                        snackbar(getPluralString(Res.plurals.playlist_added, picked.size, picked.size))
                        onBack()
                    }
                },
                enabled = count > 0,
                modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = downloadPrimary, contentColor = Color.White),
            ) {
                Text(pluralStringResource(Res.plurals.playlist_download_action, count, count), fontWeight = FontWeight.Bold)
            }
        }
    }
}

private val CONTENT_MAX_WIDTH = 760.dp

@Composable
private fun LoadedHeader(session: PlaylistDownloadSession, ready: Pager.State.Ready?, videos: List<YtFeedItem>, offline: Set<String>) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth().padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space8),
        verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space4),
    ) {
        Text(
            session.title,
            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8)) {
            Text(
                pluralStringResource(Res.plurals.playlist_videos_loaded, videos.size, videos.size),
                Modifier.weight(1f),
                style = TextStyle(fontSize = 13.sp, color = DesignColors.InkSecondary),
            )
            when {
                ready?.loadingMore == true -> CircularProgressIndicator(Modifier.size(18.dp), color = DesignColors.Accent, strokeWidth = 2.dp)
                ready?.continuation != null -> OutlinedButton(onClick = { session.pager.loadMore() }, shape = RoundedCornerShape(8.dp)) {
                    Text(stringResource(Res.string.playlist_load_more), color = DesignColors.Ink)
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(Res.string.cd_more), tint = DesignColors.InkSoft)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = DesignColors.Surface) {
                    val pickable = videos.filter { !it.live }
                    fun pick(label: String, apply: () -> Unit) = Choice(label, selected = false, pick = apply)
                    ChoiceMenuItems(
                        listOf(
                            pick(stringResource(Res.string.playlist_check_all)) { session.checked = pickable.map { it.id }.toSet() },
                            pick(stringResource(Res.string.playlist_check_not_downloaded)) {
                                session.checked = session.checked + pickable.filter { it.id !in offline }.map { it.id }
                            },
                            pick(stringResource(Res.string.playlist_uncheck_all)) { session.checked = emptySet() },
                            pick(stringResource(Res.string.playlist_uncheck_downloaded)) {
                                session.checked = session.checked - offline
                            },
                        ),
                    ) { menuOpen = false }
                }
            }
        }
    }
}

@Composable
private fun OptionsCard(session: PlaylistDownloadSession, languages: List<YtLocaleOption>, ffmpegAvailable: Boolean) {
    val preset = session.preset
    Column(
        Modifier
            .widthIn(max = CONTENT_MAX_WIDTH)
            .fillMaxWidth()
            .padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space8)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, DesignColors.OutlineSoft, RoundedCornerShape(12.dp))
            .padding(DesignSpacing.Space16),
        verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
    ) {
        Text(
            stringResource(Res.string.playlist_options).uppercase(),
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, color = DesignColors.InkSecondary),
        )
        val formats = if (ffmpegAvailable) {
            listOf(DownloadFormat.AUDIO_WEBM, DownloadFormat.M4A, DownloadFormat.MP3, DownloadFormat.VIDEO_MP4, DownloadFormat.VIDEO_WEBM)
        } else {
            listOf(DownloadFormat.M4A)
        }
        OptionLine(stringResource(Res.string.download_format), formatName(preset.format)) { close ->
            ChoiceMenuItems(formats.map { f -> Choice(formatName(f), f == preset.format) { session.preset = preset.copy(format = f) } }, close)
        }
        if (preset.format.kind == DownloadKind.VIDEO) {
            OptionLine(stringResource(Res.string.download_quality), heightLabel(preset.height ?: 1080)) { close ->
                ChoiceMenuItems(PLAYLIST_HEIGHTS.map { h -> Choice(heightLabel(h), h == preset.height) { session.preset = preset.copy(height = h) } }, close)
            }
        }
        val default = stringResource(Res.string.download_default_track)
        OptionLine(stringResource(Res.string.download_audio_language), languageName(preset.audioLanguage, languages) ?: default) { close ->
            ChoiceMenuItems(
                listOf(Choice(default, preset.audioLanguage == null) { session.preset = preset.copy(audioLanguage = null) }) +
                    languages.map { lang -> Choice(lang.label, lang.code == preset.audioLanguage) { session.preset = preset.copy(audioLanguage = lang.code) } },
                close,
            )
        }
        if (ffmpegAvailable) {
            val off = stringResource(Res.string.download_captions_off)
            OptionLine(stringResource(Res.string.download_captions), languageName(preset.captionLanguage, languages) ?: off) { close ->
                ChoiceMenuItems(
                    listOf(Choice(off, preset.captionLanguage == null) { session.preset = preset.copy(captionLanguage = null) }) +
                        languages.map { lang -> Choice(lang.label, lang.code == preset.captionLanguage) { session.preset = preset.copy(captionLanguage = lang.code) } },
                    close,
                )
            }
        }
    }
}

@Composable
private fun OptionLine(title: String, value: String, menu: @Composable (close: () -> Unit) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16)) {
        Text(title, Modifier.width(112.dp), style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink))
        Box(Modifier.weight(1f)) { DropdownField(value) { close -> menu(close) } }
    }
}

@Composable
private fun VideoCheckRow(video: YtFeedItem, checked: Boolean, downloaded: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .widthIn(max = CONTENT_MAX_WIDTH)
            .fillMaxWidth()
            .toggleable(value = checked, enabled = !video.live, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = DesignSpacing.Space8, vertical = DesignSpacing.Space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
    ) {
        Checkbox(
            checked = checked && !video.live,
            onCheckedChange = null,
            enabled = !video.live,
            colors = CheckboxDefaults.colors(checkedColor = DesignColors.Accent),
        )
        Box(Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            ThumbImage(video.thumbs, 96.dp, RoundedCornerShape(6.dp)) { ThumbPlaceholder(Icons.Outlined.Image, 20.dp) }
            Badges(video, Modifier.align(Alignment.BottomEnd).padding(DesignSpacing.Space2), textSize = 10, iconSize = 12.dp)
            if (downloaded) AvailableOfflineMark(Modifier.align(Alignment.TopStart))
        }
        Column(Modifier.weight(1f)) {
            Text(
                video.title,
                style = TextStyle(fontSize = 14.sp, color = if (video.live) DesignColors.InkFaint else DesignColors.Ink),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            video.subtitleLine1?.let {
                Text(it, style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** YouTube's own language menu (labelled in the app language); empty until loaded or if it fails. */
@Composable
private fun rememberYtLanguages(graph: AppGraph): List<YtLocaleOption> {
    val languages by produceState(emptyList<YtLocaleOption>(), graph) {
        value = try {
            graph.engines.ytLocaleOptions(graph.logger).languages
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            graph.logger?.fork("PlaylistDownload")?.log(Logger.Level.WARN, "language list failed; offering Default / Off only", e)
            emptyList()
        }
    }
    return languages
}

private fun languageName(code: String?, languages: List<YtLocaleOption>): String? =
    code?.let { c -> languages.firstOrNull { it.code == c }?.label ?: c }

/** "M4A · Audio only", "MP4 · Video" — WebM exists as both. */
@Composable
private fun formatName(format: DownloadFormat): String {
    val kind = stringResource(if (format.kind == DownloadKind.AUDIO) Res.string.download_audio_only else Res.string.download_video)
    return "${format.displayName()} · $kind"
}

private fun heightLabel(height: Int): String = when {
    height >= 4320 -> "8K"
    height >= 2160 -> "4K"
    else -> "${height}p"
}
