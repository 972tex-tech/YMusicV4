package io.ymusic.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import io.ymusic.mediastore.model.MediaStoreItem
import io.ymusic.ui.DevicePlaylist
import io.ymusic.ui.DevicePlaylistMatch
import io.ymusic.ui.DevicePlaylistSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The playlists other apps registered in MediaStore, matched to Library items (which the scanner
 * keyed by SAF document uri): first by path — MediaStore's volume + relative path + name is exactly
 * the external-storage provider's document id — then by same size and a duration within
 * [DURATION_TOLERANCE_MS], since MediaStore's extractor and ffmpeg can round durations differently.
 */
@Suppress("DEPRECATION") // MediaStore.Audio.Playlists: deprecated in API 31, still served
internal class AndroidDevicePlaylists(
    private val context: Context,
    private val requestPermission: suspend (String) -> Boolean,
) : DevicePlaylistSource {

    private val permission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    override suspend fun playlists(): List<DevicePlaylist>? {
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (!granted && !requestPermission(permission)) return null
        return withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val ids = mutableListOf<Pair<Long, String>>()
            resolver.query(
                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME),
                null,
                null,
                MediaStore.Audio.Playlists.NAME,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)?.trim()
                    if (!name.isNullOrEmpty()) ids += cursor.getLong(0) to name
                }
            }
            ids.map { (id, name) ->
                val count = resolver.query(membersUri(id), arrayOf(MediaStore.Audio.Playlists.Members._ID), null, null, null)
                    ?.use { it.count } ?: 0
                DevicePlaylist(id, name, count)
            }
        }
    }

    override suspend fun match(playlistId: Long, library: List<MediaStoreItem>): DevicePlaylistMatch = withContext(Dispatchers.IO) {
        val byDocumentId = HashMap<String, MediaStoreItem>()
        for (item in library) documentId(item)?.let { byDocumentId.putIfAbsent(it.lowercase(), item) }
        val bySize = library.groupBy { it.fileSize }

        val codes = mutableListOf<String>()
        var total = 0
        val columns = buildList {
            add(MediaStore.Audio.Playlists.Members.DATA)
            add(MediaStore.Audio.Playlists.Members.SIZE)
            add(MediaStore.Audio.Playlists.Members.DURATION)
            if (Build.VERSION.SDK_INT >= 29) {
                add(MediaStore.Audio.Playlists.Members.VOLUME_NAME)
                add(MediaStore.Audio.Playlists.Members.RELATIVE_PATH)
                add(MediaStore.Audio.Playlists.Members.DISPLAY_NAME)
            }
        }
        context.contentResolver.query(
            membersUri(playlistId),
            columns.toTypedArray(),
            null,
            null,
            MediaStore.Audio.Playlists.Members.PLAY_ORDER,
        )?.use { cursor ->
            val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.Members.DATA)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.Members.SIZE)
            val durationIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.Members.DURATION)
            val volumeIdx = cursor.getColumnIndex(MediaStore.Audio.Playlists.Members.VOLUME_NAME)
            val relativeIdx = cursor.getColumnIndex(MediaStore.Audio.Playlists.Members.RELATIVE_PATH)
            val nameIdx = cursor.getColumnIndex(MediaStore.Audio.Playlists.Members.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                total++
                val fromColumns = if (volumeIdx >= 0 && relativeIdx >= 0 && nameIdx >= 0) {
                    documentIdOf(cursor.getString(volumeIdx), cursor.getString(relativeIdx), cursor.getString(nameIdx))
                } else {
                    null
                }
                val fromPath = cursor.getString(dataIdx)?.let(::documentIdOfPath)
                val byPath = listOfNotNull(fromColumns, fromPath).firstNotNullOfOrNull { byDocumentId[it.lowercase()] }
                val size = cursor.getLong(sizeIdx)
                val duration = cursor.getLong(durationIdx)
                val item = byPath ?: bySize[size]?.firstOrNull { candidate ->
                    val candidateDuration = candidate.durationMs ?: return@firstOrNull false
                    abs(candidateDuration - duration) <= DURATION_TOLERANCE_MS
                }
                item?.let { codes += it.playlistCode }
            }
        }
        DevicePlaylistMatch(codes.distinct(), total)
    }

    private fun membersUri(playlistId: Long) = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId)

    private fun documentId(item: MediaStoreItem): String? {
        val uri = android.net.Uri.parse(item.uri.toString())
        return if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri) else null
    }

    private companion object {
        const val DURATION_TOLERANCE_MS = 2_000L

        /** MediaStore volume `external_primary` is the provider's `primary` root; another volume's
         *  name is its lowercase FAT uuid, which the provider spells uppercase. */
        fun documentIdOf(volume: String?, relativePath: String?, displayName: String?): String? {
            if (volume.isNullOrEmpty() || relativePath == null || displayName.isNullOrEmpty()) return null
            val root = if (volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) "primary" else volume.uppercase()
            return "$root:$relativePath$displayName"
        }

        /** `/storage/emulated/0/Music/a.mp3` → `primary:Music/a.mp3`; `/storage/1A2B-3C4D/x.mp3` → `1A2B-3C4D:x.mp3`. */
        fun documentIdOfPath(path: String): String? {
            val primary = "/storage/emulated/0/"
            if (path.startsWith(primary)) return "primary:" + path.removePrefix(primary)
            val rest = path.removePrefix("/storage/").takeIf { it != path } ?: return null
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            return rest.substring(0, slash) + ":" + rest.substring(slash + 1)
        }
    }
}
