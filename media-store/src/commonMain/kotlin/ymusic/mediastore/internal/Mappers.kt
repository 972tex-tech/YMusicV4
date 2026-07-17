package ymusic.mediastore.internal

import kmpcommon.uri.Uri
import ymusic.mediastore.model.MediaStoreBucket
import ymusic.mediastore.model.MediaStoreItem
import ymusic.mediastore.model.Playlist
import ymusic.mediastore.model.PlaylistItem
import ymusic.mediastore.db.Bucket as DbBucket
import ymusic.mediastore.db.MediaItem as DbMediaItem
import ymusic.mediastore.db.Playlist as DbPlaylist
import ymusic.mediastore.db.PlaylistItem as DbPlaylistItem

// SQLite stores every integer as Long; narrow the fields the model exposes as Int.
internal fun DbBucket.toModel() = MediaStoreBucket(
    id = id,
    parentId = parentId,
    uri = Uri.parse(uri),
    name = name,
    aliasName = aliasName,
)

internal fun DbMediaItem.toModel() = MediaStoreItem(
    id = id,
    bucketId = bucketId,
    uri = Uri.parse(uri),
    title = title,
    mimeType = mimeType,
    artist = artist,
    album = album,
    genre = genre,
    trackNumber = trackNumber?.toInt(),
    year = year?.toInt(),
    durationMs = durationMs,
    audioCodec = audioCodec,
    videoCodec = videoCodec,
    subtitleCodec = subtitleCodec,
    bitrate = bitrate?.toInt(),
    width = width?.toInt(),
    height = height?.toInt(),
    thumbnailStreamIndex = thumbnailStreamIndex?.toInt(),
    youtubeVid = youtubeVid,
    youtubeItags = youtubeItags,
    fileSize = fileSize,
    lastModified = lastModified,
)

internal fun DbPlaylist.toModel() = Playlist(id = id, name = name)

internal fun DbPlaylistItem.toModel() = PlaylistItem(
    playlistId = playlistId,
    code = code,
    sortKey = sortKey,
    addedAt = addedAt,
)
