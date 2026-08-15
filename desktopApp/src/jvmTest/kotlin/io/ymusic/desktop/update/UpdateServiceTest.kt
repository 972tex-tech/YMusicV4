package io.ymusic.desktop.update

import com.sun.net.httpserver.HttpServer
import kmphttp.KmpHttpClient
import kmphttp.create
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole loop against the mock service: check → plan → download → verify → stage → swap.
 * Everything here is real except the release itself — the same code paths a shipped app runs.
 */
class UpdateServiceTest {

    private val tmp = File("build/test-update-svc-${System.nanoTime()}").apply { mkdirs() }
    private val serverDir = File(tmp, "server").apply { mkdirs() }
    /** A jpackage install in the Windows/Linux shape (app/ at the root), which is also what the
     *  mock service's manifest paths use. */
    private val install = File(tmp, "YMusic").apply {
        File(this, "app").mkdirs()
        File(this, "app/YMusic.cfg").writeText("[Application]\napp.classpath=\u0024APPDIR/old.jar\n")
    }
    private var server: HttpServer? = null
    private val client: KmpHttpClient = KmpHttpClient.create { }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun cleanup() {
        server?.stop(0)
        tmp.deleteRecursively()
    }

    private fun service(versionCode: Int, autoDownload: Boolean = false): Pair<UpdateService, String> {
        val s = MockUpdateServer.start(0, serverDir, 99).also { server = it }
        val base = "http://localhost:${s.address.port}"
        val key = File(serverDir, "dev-update-key").readLines()[0]
        return UpdateService(
            client = client,
            manifestUrl = "$base/update.json",
            publicKey = key,
            currentVersionCode = versionCode,
            scope = scope,
            autoDownload = { autoDownload },
            installRoot = install,
            platformKey = "mac-x64",
        ) to base
    }

    @Test
    fun an_older_install_sees_the_update_and_stages_it_verified() = runBlocking {
        val (service, _) = service(versionCode = 1)
        service.check()

        val available = service.state.value
        assertTrue(available is UpdateState.Available, "expected Available, got $available")
        assertEquals(99, available.release.versionCode)

        // Download through the real client, against blobs served by hash.
        val release = UpdateManifest.parseVerified(fetchManifest(), devPublicKey(), "mac-x64")!!
        service.download(release)

        val ready = service.state.value
        assertTrue(ready is UpdateState.Ready, "expected Ready, got $ready")
        // The release's own directory holds the classpath files, prefix-stripped, each matching
        // the signed manifest — that is what makes the pointer switch safe.
        assertEquals("app-99", ready.staged.name)
        for (entry in release.files.filter { it.path.startsWith("app/") }) {
            val f = File(ready.staged, entry.path.removePrefix("app/"))
            assertTrue(f.isFile, "${ready.staged.name} is missing ${entry.path}")
            assertEquals(entry.sha256, sha256Of(f), "${entry.path} does not match the manifest")
        }
        assertTrue(File(ready.staged, ".ymusic-ready").isFile, "the marker is written after verifying")
    }

    @Test
    fun a_current_install_is_told_there_is_nothing_to_do() = runBlocking {
        val (service, _) = service(versionCode = 99)
        service.check()
        assertEquals(UpdateState.Idle, service.state.value)
    }

    @Test
    fun a_manifest_signed_by_the_wrong_key_is_refused_not_ignored() = runBlocking {
        val s = MockUpdateServer.start(0, serverDir, 99).also { server = it }
        val foreign = java.util.Base64.getEncoder().encodeToString(
            java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded,
        )
        val service = UpdateService(
            client = client,
            manifestUrl = "http://localhost:${s.address.port}/update.json",
            publicKey = foreign,
            currentVersionCode = 1,
            scope = scope,
            installRoot = install,
            platformKey = "mac-x64",
        )
        service.check()
        val state = service.state.value
        assertTrue(state is UpdateState.Failed, "a bad signature must surface, got $state")
    }

    @Test
    fun a_verified_version_directory_is_adopted_after_a_restart() {
        val (service, _) = service(versionCode = 1)
        File(install, "app-42").apply {
            mkdirs()
            File(this, "x.jar").writeText("hi")
            File(this, ".ymusic-ready").writeText("42\n1.0.42\nWhat changed")
        }

        service.adoptStagedUpdate()

        val state = service.state.value
        assertTrue(state is UpdateState.Ready, "expected Ready, got $state")
        assertEquals(42, state.release.versionCode)
        assertEquals("1.0.42", state.release.versionName)
    }

    @Test
    fun a_half_downloaded_version_directory_is_not_adopted() {
        // The marker is written only after every file verified, so its absence means incomplete.
        val (service, _) = service(versionCode = 1)
        File(install, "app-42").apply { mkdirs(); File(this, "x.jar").writeText("partial") }

        service.adoptStagedUpdate()

        assertEquals(UpdateState.Idle, service.state.value)
    }

    @Test
    fun a_version_directory_we_already_run_is_not_offered() {
        val (service, _) = service(versionCode = 50)
        File(install, "app-42").apply {
            mkdirs()
            File(this, ".ymusic-ready").writeText("42\n1.0.42\n")
        }

        service.adoptStagedUpdate()

        assertEquals(UpdateState.Idle, service.state.value)
    }

    private fun devPublicKey(): String = File(serverDir, "dev-update-key").readLines()[0]

    private fun fetchManifest(): String =
        java.net.URI("http://localhost:${server!!.address.port}/update.json").toURL()
            .openStream().use { it.readBytes().decodeToString() }
}
