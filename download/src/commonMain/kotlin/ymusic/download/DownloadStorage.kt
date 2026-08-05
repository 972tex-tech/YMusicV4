package ymusic.download

import ymusic.ytmedia.sabr.DownloadFile

/**
 * Abstracts **where** a download's bytes live so the pipeline never touches a concrete `FileSystem` and
 * never assumes the final file is a `file://` path. The shell backs it per platform: okio files on
 * desktop/iOS, SAF documents on Android (a Library folder → `content://`, TODO).
 *
 * Elementary streams and ffmpeg I/O go to local [scratch] files — ffmpeg needs a real path, and a
 * downloader resumes from an existing scratch's [DownloadFile.length]. [publish] then hands the finished
 * bytes to the final destination and returns its **uri** (`file://`/path or `content://`), which is what
 * the task records and the library/player consume.
 */
interface DownloadStorage {

    /** A local scratch file, created on first write. Stable for [name] so a paused download resumes into
     *  the same bytes. A task's files live under a `"<taskId>/…"` [name] so [deleteScratch] can drop them
     *  all at once. Reuses [DownloadFile] for the write side. */
    fun scratch(name: String): DownloadScratch

    /** Delete a task's entire scratch folder — every elementary/caption/out file for [taskId] in one go.
     *  Best-effort (no-op if absent). */
    fun deleteScratch(taskId: String)

    /**
     * Move [scratch]'s finished bytes into [destinationFolder] as [fileName] and return the created
     * file's uri. [destinationFolder] is the user's chosen download location (a `file://` folder, an
     * Android SAF `content://` tree, or null → the storage's default dir). A `file://`/null destination
     * is an okio rename/copy; a non-`file:` one is delegated to a [ContentPublisher] (Android SAF).
     */
    suspend fun publish(scratch: DownloadScratch, fileName: String, destinationFolder: String?): String
}

/**
 * Writes a finished scratch file into a folder that okio can't reach — an Android SAF `content://` tree.
 * Shell-provided (needs `ContentResolver`/`DocumentsContract`); null on desktop/iOS, where `file://`
 * destinations are handled by okio directly.
 */
fun interface ContentPublisher {
    /** Create [fileName] inside [folderUri] and stream [scratchPath]'s bytes in; return the new file uri. */
    suspend fun publish(scratchPath: String, folderUri: String, fileName: String): String
}

/** A local scratch file: a [DownloadFile] to stream into, plus the local [path] ffmpeg reads/writes and a
 *  [delete] for cleanup / cancel. */
interface DownloadScratch : DownloadFile {
    /** Absolute local path, used to build an ffmpeg `file:` spec. */
    val path: String

    /** Remove the file (no-op if absent). */
    fun delete()
}
