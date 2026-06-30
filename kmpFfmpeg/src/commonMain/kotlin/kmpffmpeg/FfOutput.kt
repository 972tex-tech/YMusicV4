package kmpffmpeg

/**
 * An ffmpeg OUTPUT plus the lifecycle of whatever receives it — the write-side mirror of [FfInput].
 * For destinations ffmpeg can write by itself (`file:`, the app's `ymix:`) use [passthrough]. A
 * destination ffmpeg can't address — an in-memory buffer, or Android's `content://` (opened `"w"`) — is
 * proxied, with its lifecycle split across the same two timing points as [FfInput]:
 *  - a ymix write SERVER must be listening before ffmpeg starts, so it is launched in
 *    [FfOutputResolver.resolve] (the PRE-start phase), not here;
 *  - a `pipe:1` DRAIN needs the started process's [KmpProcess.stdout], so it starts in [onProcessStarted];
 *  - [close] finalizes: a drain must JOIN (wait until ffmpeg closed stdout and every byte reached the
 *    destination) before closing it.
 *
 * Not yet consumed by anything — added ahead of the transcode/merge work (see the I/O API design note).
 */
interface FfOutput {
    /** The argument handed to ffmpeg as the output, e.g. `file:/out.mp3`, `pipe:1`, `ymix:/tmp/s`. */
    val spec: String

    /**
     * Whether ffmpeg writes this output to **stdout** (`pipe:1`) — then the runner must keep stdout piped
     * and this output drains it. `false` (default) lets the runner discard stdout: ffmpeg writes the
     * `file:`/`ymix:` destination itself. (Mirror of how stdin is always available for an [FfInput]
     * feeder; stdout, unlike stdin, is discarded unless an output opts in here.)
     */
    val usesStdout: Boolean get() = false

    /** Post-start hook for a `pipe:1` drain: launch `process.stdout` → destination on [KmpProcess.scope]. */
    fun onProcessStarted(process: KmpProcess) {}

    /** Finalize: join the drain (all bytes flushed) / stop the server, then close the destination. */
    suspend fun close() {}

    companion object {
        /** A destination ffmpeg writes directly — [spec] verbatim, stdout unused. */
        fun passthrough(spec: String): FfOutput = object : FfOutput {
            override val spec = spec
        }
    }
}
