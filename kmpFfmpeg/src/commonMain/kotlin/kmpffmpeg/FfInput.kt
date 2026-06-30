package kmpffmpeg

import okio.Closeable

/**
 * An ffmpeg input plus the lifecycle of whatever backs it — the coroutine-shaped port of YMusic-legacy's
 * `FfIoController`. For inputs ffmpeg can open by itself (`file:`, `http:`, `pipe:`, the app's `ymix:`)
 * the [passthrough] implementation is all you need. A source ffmpeg *can't* open directly — most
 * importantly Android's `content://` — is proxied, with its lifecycle split across two timing points:
 *  - a ymix SERVER must already be listening before ffmpeg starts (ffmpeg connects to it as a client),
 *    so it is launched in [FfInputResolver.resolve] — the PRE-start phase — not here;
 *  - a `pipe:0` FEEDER needs the started process's [KmpProcess.stdin], so it starts in [onProcessStarted];
 *  - [close] tears either down, so the proxy lives exactly as long as the process.
 */
interface FfInput : Closeable {
    /** The argument handed to ffmpeg as `-i`, e.g. `file:/m.flac`, `pipe:0`, `ymix:/tmp/s`. */
    val spec: String

    /**
     * Post-start hook for pipe-backed sources: start feeding [KmpProcess.stdin], tied to [KmpProcess.scope]
     * so it dies with the process. Server-backed (ymix) sources instead set up in
     * [FfInputResolver.resolve] — a server must be listening *before* ffmpeg starts. Default: nothing.
     */
    fun onProcessStarted(process: KmpProcess) {}

    companion object {
        /** A source ffmpeg opens directly — [spec] is used verbatim, with no side channel. */
        fun passthrough(spec: String): FfInput = object : FfInput {
            override val spec = spec
            override fun close() = Unit
        }
    }
}
