package kmpffmpeg

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The one smoke-test suite for [KmpProcess] + [kmpffmpeg.metadata.FfmpegMetadataGetter]. The `@Test`
 * methods and their shared bodies ([ffmpegSmokeChecks] et al.) live here; each platform supplies only
 * [config] (binary + fixtures) via a thin concrete subclass, so the SAME tests run on every target that
 * can spawn a process: desktop JVM, desktop-native, and the Android device.
 *
 * This source set is shared two ways because the new AGP KMP plugin puts the on-device test set in a
 * separate source-set tree that can't `dependsOn` it: the host (jvm + desktop-native) inherits it via
 * the normal `processTest` hierarchy, and `androidDeviceTest` pulls the same files in via `srcDir`.
 * iOS never sees it (it can't exec a process).
 */
abstract class FfmpegSmokeTest {
    /** Platform binary + fixture locations; the binary is null when this platform ships none (→ skip). */
    abstract val config: FfmpegTestConfig

    @Test fun runFfmpegAndCaptureText() = runTest { ffmpegSmokeChecks(config) }

    @Test fun readsMediaMetadata() = runTest { ffmpegMetadataChecks(config) }

    @Test fun discardsStreamsWithoutHang() = runTest { ffmpegDiscardCheck(config) }

    @Test fun capturesLargePipedStdout() = runTest { ffmpegLargeStdoutCheck(config) }

    @Test fun feedsStdinPipe() = runTest { ffmpegStdinPipeCheck(config) }

    @Test fun passesArgsWithSpacesVerbatim() = runTest { ffmpegArgWithSpaceCheck(config) }

    @Test fun readsMetadataViaGetter() = runTest { ffmpegMetadataGetterChecks(config) }

    @Test fun routesThroughInputResolver() = runTest { ffmpegInputResolverChecks(config) }

    @Test fun probesEncodedFileUriWithSpaces() = runTest { ffmpegSpacedFilePathCheck(config) }
}
