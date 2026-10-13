package io.ymusic.download

import io.ymusic.ytmedia.sabr.DownloadFile
import okio.Source

/**
 * Abstracts **where** a download's bytes live so the pipeline never touches a concrete `FileSystem` and
 * never assumes the final file is a `file://` path.
 *
 * Elementary streams and ffmpeg I/O go to local [scratch] files — ffmpeg needs a real path, and a
 * downloader resumes from an existing scratch's [DownloadFile.length]. [publish] then hands the finished
 * bytes to the chosen destination folder and returns the created file's **uri** (`file://` or, on
 * platforms that support them, `content://`), which is what the task records and the library/player consume.
 */
interface DownloadStorage {

    /** A local scratch file [name] (e.g. `"audio"`, `"out"`) belonging to [taskId], created on first
     *  write. Stable per (taskId, name) so a paused download resumes into the same bytes. All of a task's
     *  files live together so [deleteScratch] can drop them at once. Reuses [DownloadFile] for the write
     *  side. */
    fun scratch(taskId: String, name: String): DownloadScratch

    /** Delete a task's entire scratch folder — every elementary/caption/out file for [taskId] in one go.
     *  Best-effort (no-op if absent). */
    fun deleteScratch(taskId: String)

    /** The task ids that currently own a scratch folder — for pruning folders whose task is gone. */
    fun scratchTaskIds(): List<String> = emptyList()

    /**
     * Move [scratch]'s finished bytes into [destinationFolder] as [fileName] and return the created file's
     * uri. [destinationFolder] is the user's chosen download-location folder uri — always concrete (the UX
     * requires picking one before a download runs). A `file://` folder is handled directly; other schemes
     * (e.g. Android SAF `content://`) are up to the platform storage subclass.
     */
    suspend fun publish(scratch: DownloadScratch, fileName: String, destinationFolder: String): String
}

/** A local scratch file: a [DownloadFile] to stream into, plus the local [path] ffmpeg reads/writes and a
 *  [delete] for cleanup / cancel. */
interface DownloadScratch : DownloadFile {
    /** Absolute local path, used to build an ffmpeg `file:` spec. */
    val path: String

    /** Read the file from the start. */
    fun source(): Source

    /** Remove the file (no-op if absent). */
    fun delete()
}
