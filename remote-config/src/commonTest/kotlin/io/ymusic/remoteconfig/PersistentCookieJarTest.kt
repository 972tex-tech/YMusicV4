package io.ymusic.remoteconfig

import kmphttp.Cookie
import kmphttp.CookieBuilder
import kmphttp.toHttpUrlOrNull
import okio.ByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class PersistentCookieJarTest {

    private val fs = FakeFileSystem()
    private val file = "/cookies/api.json".toPath()
    private val url = requireNotNull("https://cfg.example/v1/config".toHttpUrlOrNull())
    private val future = Clock.System.now().toEpochMilliseconds() + 24 * 60 * 60 * 1000L

    private fun store() = PersistentCookieStore(FileDocument(fs, file))

    /** A plain file document, so the tests can look at what is stored. */
    private class FileDocument(private val fs: FileSystem, private val file: Path) : CookieDocument {
        override fun read(): ByteString? = if (fs.exists(file)) fs.read(file) { readByteString() } else null

        override fun write(document: ByteString) {
            fs.createDirectories(file.parent!!)
            fs.write(file) { write(document) }
        }

        override fun delete() = fs.delete(file, mustExist = false)
    }

    private fun cookie(
        name: String = "act",
        value: String = "2026-08-19",
        expiresAt: Long? = future,
        domain: String = "cfg.example",
    ): Cookie {
        val builder = CookieBuilder().name(name).value(value).hostOnlyDomain(domain).path("/")
        expiresAt?.let { builder.expiresAt(it) }
        return builder.build()
    }

    @Test
    fun a_persistent_cookie_survives_a_restart() {
        store().saveFromResponse(listOf(cookie()))

        // A second store over the same file is the next launch of the app.
        val replayed = store().loadForRequest(url)

        assertEquals(listOf("act" to "2026-08-19"), replayed.map { it.name to it.value })
        assertTrue(replayed.single().persistent)
    }

    @Test
    fun a_session_cookie_works_in_memory_but_never_touches_disk() {
        val first = store()
        first.saveFromResponse(listOf(cookie(name = "sess", expiresAt = null)))

        assertEquals(listOf("sess"), first.loadForRequest(url).map { it.name })
        assertFalse(fs.exists(file), "nothing persistent, nothing written")
        assertEquals(emptyList(), store().loadForRequest(url), "and a restart starts clean")
    }

    @Test
    fun replacement_is_what_the_next_launch_sees() {
        store().saveFromResponse(listOf(cookie(value = "2026-08-19")))
        store().saveFromResponse(listOf(cookie(value = "2026-08-20")))

        assertEquals("2026-08-20", store().loadForRequest(url).single().value)
    }

    @Test
    fun an_already_expired_cookie_evicts_its_predecessor() {
        val jar = store()
        jar.saveFromResponse(listOf(cookie()))
        jar.saveFromResponse(listOf(cookie(expiresAt = 1L))) // expired long ago = delete order

        assertEquals(emptyList(), jar.loadForRequest(url))
        assertEquals(emptyList(), store().loadForRequest(url), "the deletion reached the file too")
    }

    @Test
    fun an_expired_cookie_on_disk_is_pruned_at_load() {
        fs.createDirectories(file.parent!!)
        fs.write(file) {
            writeUtf8(
                """{"cookies":[{"name":"old","value":"x","expiresAt":1,"domain":"cfg.example",""" +
                    """"path":"/","secure":false,"httpOnly":false,"hostOnly":true}]}""",
            )
        }

        assertEquals(emptyList(), store().loadForRequest(url))
    }

    @Test
    fun a_corrupt_file_means_an_empty_jar_and_the_file_is_gone() {
        fs.createDirectories(file.parent!!)
        fs.write(file) { writeUtf8("{not json") }

        assertEquals(emptyList(), store().loadForRequest(url))
        assertFalse(fs.exists(file))
    }

    @Test
    fun cookies_stay_scoped_to_their_domain() {
        store().saveFromResponse(listOf(cookie(domain = "other.example")))

        assertEquals(emptyList(), store().loadForRequest(url))
    }
}
