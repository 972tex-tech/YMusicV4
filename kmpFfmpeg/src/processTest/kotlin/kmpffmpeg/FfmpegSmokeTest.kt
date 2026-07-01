package kmpffmpeg

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Config holder shared by the two smoke suites below. Each platform supplies only [config] (binary +
 * fixtures) via a thin concrete subclass, so the SAME suites run on every target that can spawn a
 * process: desktop JVM, desktop-native, and the Android device.
 *
 * These suites are shared two ways because the new AGP KMP plugin puts the on-device test set in a
 * separate source-set tree that can't `dependsOn` `processTest`: the host (jvm + desktop-native)
 * inherits them via the normal `processTest` hierarchy, and `androidDeviceTest` pulls the same files in
 * via `srcDir`. iOS never sees them (it can't exec a process).
 */
abstract class FfmpegSmokeTest {
    /** Platform binary + fixture locations; the binary is null when this platform ships none (→ skip). */
    abstract val config: FfmpegTestConfig
}

/**
 * Process-level smoke suite: launches the real ffmpeg binary and exercises [KmpProcess] directly —
 * exec, piped stdout/stderr, discard, stdin feed, and argv passing. On Android the fixtures are served
 * through the ymix proxy (see [FfmpegTestConfig.inputFactory]).
 */
abstract class KmpProcessSmokeTest : FfmpegSmokeTest() {
    @Test fun runFfmpegAndCaptureText() = runTest { ffmpegSmokeChecks(config) }

    @Test fun readsMediaMetadata() = runTest { ffmpegMetadataChecks(config) }

    @Test fun discardsStreamsWithoutHang() = runTest { ffmpegDiscardCheck(config) }

    @Test fun capturesLargePipedStdout() = runTest { ffmpegLargeStdoutCheck(config) }

    @Test fun feedsStdinPipe() = runTest { ffmpegStdinPipeCheck(config) }

    @Test fun passesArgsWithSpacesVerbatim() = runTest { ffmpegArgWithSpaceCheck(config) }
}

/**
 * Getter-level smoke suite: drives [kmpffmpeg.metadata.FfmpegMetadataGetter] end to end — launch →
 * stderr capture → parse into the model, plus the [FfInputResolver] seam and percent-encoded paths.
 */
abstract class FfmpegMetadataGetterSmokeTest : FfmpegSmokeTest() {
    @Test fun readsMetadataViaGetter() = runTest { ffmpegMetadataGetterChecks(config) }

    @Test fun routesThroughInputResolver() = runTest { ffmpegInputResolverChecks(config) }

    @Test fun probesEncodedFileUriWithSpaces() = runTest { ffmpegSpacedFilePathCheck(config) }
}
