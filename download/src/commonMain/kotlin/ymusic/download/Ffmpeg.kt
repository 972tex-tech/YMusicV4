package ymusic.download

import kmpcommon.error.TechnicalException
import kmpffmpeg.KmpProcessBuilder
import kotlinx.coroutines.async
import okio.buffer

/** One `-progress` line → the output time in microseconds, or null for any other key. ffmpeg emits a
 *  block of `key=value` lines per update; `out_time_us` is the reliable elapsed-output-time field. */
internal fun parseFfmpegProgressUs(line: String): Long? {
    val value = line.substringAfter("out_time_us=", missingDelimiterValue = "")
    if (value.isEmpty()) return null
    return value.trim().toLongOrNull()
}

/**
 * Thin async wrapper over [KmpProcessBuilder] for the operations the downloader needs — transcode a
 * best-audio stream to MP3, remux to M4A, and mux separate audio+video elementary streams into one
 * container — each optionally tagging the output with container metadata (`-metadata k=v`). There is no
 * ffmpeg DSL in the repo; you assemble argv and await the exit code. File I/O uses `file:` specs (no
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

    // The output container is named explicitly with `-f` so it doesn't depend on the output filename's
    // extension — scratch/publish names (and content:// targets) may have none.

    /** opus/webm/m4a → mp3 via libmp3lame (VBR ~q2, drops any cover/video stream). */
    suspend fun toMp3(
        input: String,
        output: String,
        metadata: List<Pair<String, String>> = emptyList(),
        onProgressUs: ((Long) -> Unit)? = null,
    ) = run(
        listOf("-i", input.ffSpec(), "-vn", "-c:a", "libmp3lame", "-q:a", "2") + metaArgs(metadata) +
            listOf("-f", "mp3", output.ffSpec()),
        onProgressUs,
    )

    /** best audio → m4a: stream-copy when already AAC ([copy]=true), else transcode to AAC. */
    suspend fun toM4a(
        input: String,
        output: String,
        copy: Boolean,
        metadata: List<Pair<String, String>> = emptyList(),
        onProgressUs: ((Long) -> Unit)? = null,
    ) = run(
        buildList {
            add("-i"); add(input.ffSpec()); add("-vn")
            if (copy) { add("-c:a"); add("copy") } else { add("-c:a"); add("aac"); add("-b:a"); add("192k") }
            addAll(metaArgs(metadata))
            add("-f"); add("ipod"); add(output.ffSpec()) // ipod = the m4a muxer
        },
        onProgressUs,
    )

    /** Mux VP9 video + Opus audio into one WebM, stream-copying both (no re-encode). */
    suspend fun muxWebm(
        video: String,
        audio: String,
        output: String,
        metadata: List<Pair<String, String>> = emptyList(),
        onProgressUs: ((Long) -> Unit)? = null,
    ) = run(
        listOf("-i", video.ffSpec(), "-i", audio.ffSpec(), "-map", "0:v:0", "-map", "1:a:0", "-c", "copy") +
            metaArgs(metadata) + listOf("-f", "webm", output.ffSpec()),
        onProgressUs,
    )

    /** Mux video (copied) + audio (transcoded to AAC — broad MP4 support) into one MP4. */
    suspend fun muxMp4(
        video: String,
        audio: String,
        output: String,
        metadata: List<Pair<String, String>> = emptyList(),
        onProgressUs: ((Long) -> Unit)? = null,
    ) = run(
        listOf(
            "-i", video.ffSpec(), "-i", audio.ffSpec(),
            "-map", "0:v:0", "-map", "1:a:0",
            "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
            "-movflags", "+faststart",
        ) + metaArgs(metadata) + listOf("-f", "mp4", output.ffSpec()),
        onProgressUs,
    )

    /**
     * Run ffmpeg with the given args (a `-hide_banner -loglevel error -y` prefix is added). Throws on
     * non-zero exit with the captured stderr for diagnostics. When [onProgressUs] is set, `-progress
     * pipe:1` is added and each progress tick's output time (microseconds) is delivered to it.
     */
    suspend fun run(args: List<String>, onProgressUs: ((Long) -> Unit)? = null) {
        val builder = KmpProcessBuilder(binaryPath)
            .appendArg("-hide_banner", "-loglevel", "error", "-y")
        if (onProgressUs != null) builder.appendArg("-progress", "pipe:1", "-nostats")
        builder.appendArgs(args).setEnv(env)
        if (onProgressUs == null) builder.discardStdout()
        val process = builder.start()
        val stderr = process.scope.async { process.stderr.buffer().readUtf8() }
        val progress = onProgressUs?.let { cb ->
            process.scope.async {
                val source = process.stdout.buffer()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    parseFfmpegProgressUs(line)?.let(cb)
                }
            }
        }
        val code = process.exitCode.await()
        runCatching { progress?.await() }
        val log = runCatching { stderr.await() }.getOrDefault("")
        process.close()
        if (code != 0) throw TechnicalException("E70021", "ffmpeg exited $code:\n$log")
    }

    /** `-metadata k=v` per non-blank tag; applied to the output container. */
    private fun metaArgs(metadata: List<Pair<String, String>>): List<String> =
        metadata.filter { it.second.isNotBlank() }.flatMap { (k, v) -> listOf("-metadata", "$k=$v") }

    private fun String.ffSpec(): String = "file:$this"
}
