package kmpffmpeg

/**
 * Where the smoke checks find a runnable ffmpeg and the committed media fixtures, plus how a fixture is
 * handed to ffmpeg. Supplied by each platform's concrete [FfmpegSmokeTest] subclass — host (JVM +
 * desktop-native) from build-time generated paths opening fixtures as plain `file:`, the Android device
 * from the instrumentation context at runtime, serving every fixture through the **ymix** proxy.
 *
 * (Not an `expect`/`actual`: the on-device test source set lives in a separate source-set tree from
 * `processTest`, so it can neither `dependsOn` it nor pair an `actual` with it — the shared smoke logic
 * is shared by physical `srcDir`, and the per-platform binary/fixture lookup is injected via this.)
 */
class FfmpegTestConfig(
    /** Absolute path of a runnable ffmpeg, or null when this platform has none (→ checks skip). */
    val binaryPath: String?,
    /** Directory holding the media fixtures (`sample_with_metadata.{flac,m4a,mka}`). */
    val testDataDir: String,
    /**
     * Turns a fixture file name into the [FfInput] the checks feed to ffmpeg (spec + lifecycle). The
     * default opens it directly as `file:` (host); Android overrides this to serve the fixture through a
     * `FfYmixProtocolServer`, so the smoke suite exercises the real `ymix` read path end-to-end.
     */
    val inputFactory: (name: String) -> FfInput = { name -> FfInput.passthrough("file:$testDataDir/$name") },
)
