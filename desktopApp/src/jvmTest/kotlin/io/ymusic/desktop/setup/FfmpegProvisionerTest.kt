package io.ymusic.desktop.setup

import com.sun.net.httpserver.HttpServer
import io.ymusic.desktop.update.MockUpdateServer
import io.ymusic.desktop.update.UpdateVerificationException
import kmphttp.KmpHttpClient
import kmphttp.RedirectOptions
import kmphttp.create
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The first run installs something it then executes, so this exercises the whole path against a
 * real binary: signed manifest → download → hash → unpack → sign → *run it*.
 *
 * Skipped wholesale when the host has no ffmpeg to publish — there is no point asserting on
 * synthesised bytes when the thing being proven is that a real one comes out runnable.
 */
class FfmpegProvisionerTest {

    private val dir = File("build/test-tools-${System.nanoTime()}")
    private var server: HttpServer? = null

    /** Any ffmpeg on this machine, used as the artifact the mock service publishes. */
    private val hostFfmpeg: File? = sequenceOf(
        File("ffmpeg-prebuilt-ymusic/macos/ffmpeg"),
        File("../ffmpeg-prebuilt-ymusic/macos/ffmpeg"),
        File("/opt/homebrew/bin/ffmpeg"),
        File("/usr/local/bin/ffmpeg"),
    ).firstOrNull { it.canExecute() }

    private val platformKey = io.ymusic.desktop.update.currentPlatformKey()

    @AfterTest fun cleanup() {
        server?.stop(0)
        dir.deleteRecursively()
    }

    /** Publishes [hostFfmpeg] for this platform and returns the base url. */
    private fun serve(): String {
        dir.mkdirs()
        hostFfmpeg!!.copyTo(File(dir, "ffmpeg-$platformKey"), overwrite = true)
        val s = MockUpdateServer.start(0, dir, 99).also { server = it }
        return "http://localhost:${s.address.port}"
    }

    private fun devPublicKey(): String = File(dir, "dev-update-key").readLines()[0]

    private fun provisioner(base: String, key: String, toolsDir: File = File(dir, "installed")) =
        FfmpegProvisioner(
            client = KmpHttpClient.create {
                defaultRedirectOptions = RedirectOptions(followRedirects = true, followSslRedirects = true)
            },
            manifestUrl = "$base/tools.json",
            publicKey = key,
            toolsDir = toolsDir,
            platformKey = platformKey,
        )

    @Test
    fun a_published_build_arrives_verified_and_actually_runs() {
        hostFfmpeg ?: return
        val base = serve()
        var lastDone = 0L
        var lastTotal = 0L

        val installed = runBlocking {
            provisioner(base, devPublicKey()).provision { done, total ->
                lastDone = done
                lastTotal = total
            }
        }

        assertTrue(installed.canExecute(), "the provisioned binary is executable")
        assertEquals(lastTotal, lastDone, "progress ends at the full size")
        assertTrue(lastTotal > 0, "a byte total was reported, so the ring can fill")

        // The point of the whole exercise: it runs.
        val process = ProcessBuilder(installed.absolutePath, "-version").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor())
        assertTrue(output.contains("ffmpeg version"), "got: ${output.take(120)}")

        // Apple Silicon will not exec an unsigned Mach-O at all, so the provisioner signs one that
        // arrives bare. (This host is Intel, where it would have run either way — the check is that
        // the signature really got applied, not that it was needed here.)
        if (isMac) {
            val verify = ProcessBuilder("codesign", "--verify", installed.absolutePath)
                .redirectErrorStream(true).start()
            verify.inputStream.readBytes()
            assertEquals(0, verify.waitFor(), "the installed binary carries a valid signature")
        }
    }

    @Test
    fun a_second_run_reuses_the_installed_copy_instead_of_downloading_again() {
        hostFfmpeg ?: return
        val base = serve()
        val toolsDir = File(dir, "installed")
        val first = runBlocking { provisioner(base, devPublicKey(), toolsDir).provision { _, _ -> } }

        // With the service stopped there is nothing left to download from: what the app calls at
        // startup is this offline lookup, not provision — provision always re-reads the manifest,
        // because that is the only way to learn a version newer than what is on disk.
        server?.stop(0)
        val found = provisioner(base, devPublicKey(), toolsDir).installed()

        assertEquals(first.absolutePath, found?.absolutePath)
    }

    @Test
    fun a_manifest_signed_by_another_key_is_refused_and_nothing_is_installed() {
        hostFfmpeg ?: return
        val base = serve()
        val stranger = Base64.getEncoder()
            .encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded)
        val toolsDir = File(dir, "installed")

        assertFailsWith<UpdateVerificationException> {
            runBlocking { provisioner(base, stranger, toolsDir).provision { _, _ -> } }
        }
        assertNull(provisioner(base, stranger, toolsDir).installed(), "nothing was left behind")
    }

    @Test
    fun a_platform_with_no_published_build_fails_rather_than_installing_someone_elses() {
        hostFfmpeg ?: return
        val base = serve()
        val other = FfmpegProvisioner(
            client = KmpHttpClient.create {},
            manifestUrl = "$base/tools.json",
            publicKey = devPublicKey(),
            toolsDir = File(dir, "installed"),
            // A key the mock publishes nothing for (only this host's key was copied in).
            platformKey = "solaris-sparc",
        )
        val failure = assertFailsWith<IllegalStateException> { runBlocking { other.provision { _, _ -> } } }
        assertTrue(failure.message!!.contains("solaris-sparc"), failure.message!!)
    }

    @Test
    fun an_unconfigured_build_reports_disabled_rather_than_fetching_anything() {
        val off = FfmpegProvisioner(KmpHttpClient.create {}, "", "", File(dir, "x"), platformKey)
        assertTrue(!off.isEnabled)
    }

    @Test
    fun a_run_killed_mid_download_leaves_nothing_a_later_run_can_trip_over() {
        hostFfmpeg ?: return
        val base = serve()
        val toolsDir = File(dir, "installed").apply { mkdirs() }
        // What closing the window mid-download leaves behind: the process dies, so no cleanup ran.
        val orphan = File(toolsDir, "mock-1.part").apply { mkdirs() }
        File(orphan, "download").writeBytes(ByteArray(4096))
        val strayVersion = File(toolsDir, "some-older.part").apply { mkdirs() }

        assertNull(provisioner(base, devPublicKey(), toolsDir).installed(), "a .part is not an install")

        val installed = runBlocking { provisioner(base, devPublicKey(), toolsDir).provision { _, _ -> } }

        assertTrue(installed.canExecute(), "the next run installs cleanly over the wreckage")
        assertFalse(orphan.exists(), "this version's staging was swept")
        assertFalse(strayVersion.exists(), "so was an unrelated one")
    }

    @Test
    fun an_interrupted_download_resumes_instead_of_starting_over() {
        val ffmpeg = hostFfmpeg ?: return
        val base = serve()
        val toolsDir = File(dir, "installed").apply { mkdirs() }
        // Exactly what closing the window mid-download leaves: a partial archive plus the marker
        // saying which build it belongs to.
        val staging = File(toolsDir, "mock-1.part").apply { mkdirs() }
        val half = ffmpeg.readBytes().copyOfRange(0, ffmpeg.length().toInt() / 2)
        File(staging, "download").writeBytes(half)
        File(staging, "source").writeText(
            "$base/download/ffmpeg-$platformKey\n" + sha256Of(ffmpeg),
        )

        var firstReport = -1L
        val installed = runBlocking {
            provisioner(base, devPublicKey(), toolsDir).provision { done, _ ->
                if (firstReport < 0) firstReport = done
            }
        }

        assertTrue(installed.canExecute(), "the resumed download still produced a runnable binary")
        assertEquals(half.size.toLong(), firstReport, "progress picked up from the bytes already on disk")
    }

    private fun sha256Of(file: File): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
}
