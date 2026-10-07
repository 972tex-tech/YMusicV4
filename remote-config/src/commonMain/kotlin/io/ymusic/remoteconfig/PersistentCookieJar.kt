package io.ymusic.remoteconfig

import kmphttp.Cookie
import kmphttp.CookieBuilder
import kmphttp.CookieJar
import kmphttp.HttpUrl
import kmphttp.json.JsonWriter
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import projectcommon.json.boolOrNull
import projectcommon.json.longOrNull
import projectcommon.json.objects
import projectcommon.json.string
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A [CookieJar] whose persistent cookies survive restarts in a small JSON file (its first
 * customer: the API service's state cookies). Session (non-persistent) cookies stay memory-only,
 * per spec.
 *
 * The file is rewritten whole on change — it holds a handful of cookies and changes rarely, and
 * the whole-file tmp + atomicMove is what makes a crash leave the previous complete document
 * rather than a torn one. A corrupt file is discarded and the jar starts empty. One instance per
 * file, ever.
 *
 * expect/actual only because [CookieJar] itself differs per platform (okhttp's blocking interface
 * on the JVM, a suspend interface elsewhere); both actuals are unlocked one-liners — the store
 * below synchronizes itself with one common lock.
 */
expect class PersistentCookieJar(file: Path, fileSystem: FileSystem) : CookieJar

/** The common implementation both thin actuals delegate to. */
@OptIn(ExperimentalTime::class)
internal class PersistentCookieStore(
    private val file: Path,
    private val fileSystem: FileSystem,
) : SynchronizedObject() {

    /** Keyed by (name, domain, path) — the cookie identity — so replacement is O(1), not a scan. */
    private val cookies = LinkedHashMap<CookieKey, Cookie>()
    private var loaded = false

    fun saveFromResponse(newCookies: List<Cookie>) = synchronized(this) {
        loadIfNeeded()
        val now = Clock.System.now().toEpochMilliseconds()
        var dirty = false
        for (cookie in newCookies) {
            val key = CookieKey(cookie.name, cookie.domain, cookie.path)
            val keep = !cookie.persistent || cookie.expiresAt > now
            val previous = if (keep) cookies.put(key, cookie) else cookies.remove(key)
            // Only a change to what the FILE holds is worth a write: a persistent cookie came in,
            // or one already on disk was replaced or removed.
            dirty = dirty || (keep && cookie.persistent) || previous?.persistent == true
        }
        if (dirty) persist()
    }

    fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(this) {
        loadIfNeeded()
        val now = Clock.System.now().toEpochMilliseconds()
        var expired = false
        val iterator = cookies.values.iterator()
        while (iterator.hasNext()) {
            val cookie = iterator.next()
            if (cookie.persistent && cookie.expiresAt <= now) {
                iterator.remove()
                expired = true
            }
        }
        if (expired) persist()
        cookies.values.filter { it.matches(url) }
    }

    // The file is read whole as a string, so the DOM parser applies (the house split: streams go
    // through JsonReader, strings through kotlinx Json) — and nothing here suspends, which is
    // what the JVM's blocking CookieJar interface requires.
    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        val root = try {
            if (!fileSystem.exists(file)) return
            Json.parseToJsonElement(fileSystem.read(file) { readUtf8() }).jsonObject
        } catch (_: Throwable) {
            // Unreadable is worse than empty; failing to delete must not become a second failure.
            runCatching { fileSystem.delete(file) }
            return
        }
        root.objects(KEY_COOKIES)
            .mapNotNull { cookieFrom(it) }
            .associateByTo(cookies) { CookieKey(it.name, it.domain, it.path) }
    }

    private fun persist() {
        val tmp = file.parent?.resolve(file.name + ".tmp") ?: return
        file.parent?.let { fileSystem.createDirectories(it) }
        val writer = JsonWriter.Sync()
        with(writer) {
            beginObject()
            name(KEY_COOKIES).beginArray()
            for (cookie in cookies.values) {
                if (!cookie.persistent) continue // sessions never touch disk
                beginObject()
                name(KEY_NAME).value(cookie.name)
                name(KEY_VALUE).value(cookie.value)
                name(KEY_EXPIRES_AT).value(cookie.expiresAt)
                name(KEY_DOMAIN).value(cookie.domain)
                name(KEY_PATH).value(cookie.path)
                name(KEY_SECURE).value(cookie.secure)
                name(KEY_HTTP_ONLY).value(cookie.httpOnly)
                name(KEY_HOST_ONLY).value(cookie.hostOnly)
                endObject()
            }
            endArray()
            endObject()
        }
        fileSystem.sink(tmp).use { it.buffer().use { out -> out.writeAll(writer.buffer) } }
        fileSystem.atomicMove(tmp, file)
    }

    /** Null for a malformed entry: one bad record must not take the readable ones with it. */
    private fun cookieFrom(json: JsonObject): Cookie? {
        return try {
            val builder = CookieBuilder()
                .name(json.string(KEY_NAME))
                .value(json.string(KEY_VALUE))
                // Only persistent cookies are ever written, and expiresAt is what makes the
                // rebuilt cookie persistent again.
                .expiresAt(json.longOrNull(KEY_EXPIRES_AT) ?: return null)
                .path(json.string(KEY_PATH))
            val domain = json.string(KEY_DOMAIN)
            if (json.boolOrNull(KEY_HOST_ONLY) == true) {
                builder.hostOnlyDomain(domain)
            } else {
                builder.domain(domain)
            }
            if (json.boolOrNull(KEY_SECURE) == true) builder.secure()
            if (json.boolOrNull(KEY_HTTP_ONLY) == true) builder.httpOnly()
            builder.build()
        } catch (_: Throwable) {
            null
        }
    }

    private data class CookieKey(val name: String, val domain: String, val path: String)

    private companion object {
        const val KEY_COOKIES = "cookies"
        const val KEY_NAME = "name"
        const val KEY_VALUE = "value"
        const val KEY_EXPIRES_AT = "expiresAt"
        const val KEY_DOMAIN = "domain"
        const val KEY_PATH = "path"
        const val KEY_SECURE = "secure"
        const val KEY_HTTP_ONLY = "httpOnly"
        const val KEY_HOST_ONLY = "hostOnly"
    }
}
