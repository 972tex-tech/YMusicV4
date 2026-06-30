package kmpffmpeg

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The one smoke-test class for [KmpProcess], shared by the JVM and desktop-native targets via the
 * `processTest` source set. The bodies live in commonTest ([ffmpegSmokeChecks] et al.) so they compile
 * everywhere; only the targets that can actually exec a process get the `@Test` wrappers — iOS, where
 * `startProcess` throws, never inherits this class.
 */
class KmpProcessTest {
    @Test
    fun runFfmpegAndCaptureText() = runTest { ffmpegSmokeChecks() }

    @Test
    fun readsMediaMetadata() = runTest { ffmpegMetadataChecks() }

    @Test
    fun discardsStreamsWithoutHang() = runTest { ffmpegDiscardCheck() }

    @Test
    fun capturesLargePipedStdout() = runTest { ffmpegLargeStdoutCheck() }

    @Test
    fun feedsStdinPipe() = runTest { ffmpegStdinPipeCheck() }

    @Test
    fun passesArgsWithSpacesVerbatim() = runTest { ffmpegArgWithSpaceCheck() }

    @Test
    fun readsMetadataViaGetter() = runTest { ffmpegMetadataGetterChecks() }

    @Test
    fun routesThroughInputResolver() = runTest { ffmpegInputResolverChecks() }

    @Test
    fun probesEncodedFileUriWithSpaces() = runTest { ffmpegSpacedFilePathCheck() }
}
