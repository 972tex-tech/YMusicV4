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

    /** Move [scratch]'s finished bytes to the final destination as [fileName] and return its uri. On okio
     *  this is a rename into the downloads dir; on Android it creates the SAF document and streams in. */
    suspend fun publish(scratch: DownloadScratch, fileName: String): String
}

/** A local scratch file: a [DownloadFile] to stream into, plus the local [path] ffmpeg reads/writes and a
 *  [delete] for cleanup / cancel. */
interface DownloadScratch : DownloadFile {
    /** Absolute local path, used to build an ffmpeg `file:` spec. */
    val path: String

    /** Remove the file (no-op if absent). */
    fun delete()
}
