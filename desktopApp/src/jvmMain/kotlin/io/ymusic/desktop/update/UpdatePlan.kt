package io.ymusic.desktop.update

/**
 * What an update actually costs, decided by comparing content hashes rather than versions.
 *
 * Every file in a release is addressed by its SHA-256, so a file that did not change between two
 * releases has the same hash and is simply reused from the current install — the ~68MB bundled JVM
 * and every unchanged dependency drop out on their own, with no diffing.
 */
data class UpdatePlan(
    /** Files whose content is not already on disk. These are the only downloads. */
    val toDownload: List<FileEntry>,
    /** Files reusable from the current install (same hash, possibly at a different path). */
    val reusable: List<FileEntry>,
    /** Paths present in the install but absent from the release — deleted during the swap. */
    val obsolete: List<String>,
) {
    val downloadBytes: Long get() = toDownload.sumOf { it.size }
    val totalBytes: Long get() = downloadBytes + reusable.sumOf { it.size }

    /** A release whose files are all present is already installed byte for byte. */
    val isNoOp: Boolean get() = toDownload.isEmpty() && obsolete.isEmpty()
}

/**
 * Plan the update from what is on disk.
 *
 * [installedHashes] maps a relative path to the SHA-256 of the installed file (null/absent when the
 * file is missing). Content addressing means a file MOVED between releases still counts as
 * reusable: only hashes are compared, never paths.
 *
 * Pure by design — the swap is the dangerous part, so the decision of what to fetch is kept
 * separately testable from any I/O.
 */
fun planUpdate(installedHashes: Map<String, String>, release: Release): UpdatePlan {
    val available = installedHashes.values.toHashSet()
    val toDownload = mutableListOf<FileEntry>()
    val reusable = mutableListOf<FileEntry>()
    for (file in release.files) {
        if (file.sha256 in available) reusable += file else toDownload += file
    }
    val wanted = release.files.mapTo(hashSetOf()) { it.path }
    return UpdatePlan(
        toDownload = toDownload,
        reusable = reusable,
        obsolete = installedHashes.keys.filterNot { it in wanted },
    )
}
