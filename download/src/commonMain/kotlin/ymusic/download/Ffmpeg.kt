package ymusic.download

import kmpffmpeg.KmpProcessBuilder
import kotlinx.coroutines.async
import okio.Path
import okio.buffer

/**
 * Thin async wrapper over [KmpProcessBuilder] for the two operations the downloader needs — transcode a
 * best-audio stream to MP3, and mux separate audio+video elementary streams into one container. There is
 * no ffmpeg DSL in the repo; you assemble argv and await the exit code. File I/O uses `file:` specs (no
 * resolver needed for plain files); ffmpeg does not percent-decode, so we pass the raw path.
 *
 * Absent on iOS: `KmpProcessBuilder.start()` throws on Apple no-process targets, so an [Ffmpeg] is only
 * constructed by shells that ship a binary (Android's `libffmpeg.so`, desktop's prebuilt). The service
 * treats a null [Ffmpeg] as "ffmpeg unavailable" and restricts itself to no-transcode outputs.
 */
class Ffmpeg(
    private val binaryPath: String,
    /** The child inherits NO parent env (the process backend clears it); Linux needs LD_LIBRARY_PATH here. */
    private val env: Map<String, String> = emptyMap(),
) {

    /** opus/webm/m4a → mp3 via libmp3lame (VBR ~q2, drops any cover/video stream). */
    suspend fun toMp3(input: Path, output: Path) = run(
        listOf("-i", input.ffSpec(), "-vn", "-c:a", "libmp3lame", "-q:a", "2", output.ffSpec()),
    )

    /** best audio → m4a: stream-copy when already AAC ([copy]=true), else transcode to AAC. */
    suspend fun toM4a(input: Path, output: Path, copy: Boolean) = run(
        buildList {
            add("-i"); add(input.ffSpec()); add("-vn")
            if (copy) { add("-c:a"); add("copy") } else { add("-c:a"); add("aac"); add("-b:a"); add("192k") }
            add(output.ffSpec())
        },
    )

    /** Mux VP9 video + Opus audio into one WebM, stream-copying both (no re-encode). */
    suspend fun muxWebm(video: Path, audio: Path, output: Path) = run(
        listOf(
            "-i", video.ffSpec(), "-i", audio.ffSpec(),
            "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", output.ffSpec(),
        ),
    )

    /** Mux video (copied) + audio (transcoded to AAC — broad MP4 support) into one MP4. */
    suspend fun muxMp4(video: Path, audio: Path, output: Path) = run(
        listOf(
            "-i", video.ffSpec(), "-i", audio.ffSpec(),
            "-map", "0:v:0", "-map", "1:a:0",
            "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
            "-movflags", "+faststart", output.ffSpec(),
        ),
    )

    /** Run ffmpeg with the given args (a `-hide_banner -loglevel error -y` prefix is added). Throws on
     *  non-zero exit with the captured stderr for diagnostics. */
    suspend fun run(args: List<String>) {
        val process = KmpProcessBuilder(binaryPath)
            .appendArg("-hide_banner", "-loglevel", "error", "-y")
            .appendArgs(args)
            .setEnv(env)
            .discardStdout()
            .start()
        val stderr = process.scope.async { process.stderr.buffer().readUtf8() }
        val code = process.exitCode.await()
        val log = runCatching { stderr.await() }.getOrDefault("")
        process.close()
        if (code != 0) error("ffmpeg exited $code:\n$log")
    }

    private fun Path.ffSpec(): String = "file:$this"
}
