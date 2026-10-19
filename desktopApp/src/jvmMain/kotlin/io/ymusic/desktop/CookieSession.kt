package io.ymusic.desktop

import io.ymusic.remoteconfig.CookieDocument
import io.ymusic.remoteconfig.PersistentCookieJar
import kmpcommon.log.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.ByteString
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * What one launch runs on: the cookie jar, and the caches that go with whoever owns it. A guest's
 * caches are its own and die with the process, so a guest and the signed-in user never read each
 * other's cached responses.
 */
internal class CookieSession(
    val jar: PersistentCookieJar,
    /** Null for a guest: nothing of it is kept, so nothing to protect. */
    private val vault: CookieVault?,
    /** Root of the session's HTTP and engine caches. */
    val cacheRoot: File,
) {
    val isGuest: Boolean get() = vault == null

    /**
     * The "protect with a password" step is offered where the default key is one every app of
     * this user can recover — not on macOS, whose keychain item is bound to this app's signature.
     */
    val canProtect: Boolean get() = vault != null && !vault.isPasswordProtected && desktopOsKey() != "macos"

    suspend fun protect(password: CharArray) {
        val vault = checkNotNull(vault) { "a guest jar is not kept" }
        val seal = withContext(Dispatchers.Default) { CookieVault.newPasswordSeal(password) }
        withContext(Dispatchers.IO) { vault.protect(seal) }
    }
}

/** How the cookie store opened at launch. */
internal sealed interface CookieStoreOpen {
    class Ready(val session: CookieSession) : CookieStoreOpen

    /** Password-sealed: the launch waits for [unlock], [asGuest] or [signOut]. */
    class Locked internal constructor(
        private val vault: CookieVault,
        private val store: AppStore,
        private val dataDir: File,
        private val userKey: SecretKey?,
    ) : CookieStoreOpen {
        /** Null when [password] is wrong. */
        suspend fun unlock(password: CharArray): CookieSession? = withContext(Dispatchers.IO) {
            if (!vault.unlock(password)) return@withContext null
            CookieSession(PersistentCookieJar(vault), vault, dataDir)
        }

        suspend fun asGuest(): CookieSession = withContext(Dispatchers.IO) { guestSession() }

        /** Forgets the sealed sign-in and the caches made under it, then starts signed out. */
        suspend fun signOut(): CookieSession = withContext(Dispatchers.IO) {
            store.deleteSecret(COOKIE_JAR)
            sessionCacheDirs(dataDir).forEach { it.deleteRecursively() }
            if (userKey == null) guestSession() else userSession(store, dataDir, userKey)
        }
    }
}

/** The sealed jar's [AppStore] secret — named as the file it was before (the legacy import keeps the name). */
internal const val COOKIE_JAR = "api-cookies.json"
internal const val HTTP_CACHE_DIR = "http-cache"
internal const val ENGINE_CACHE_DIR = "engine-cache"

private fun sessionCacheDirs(root: File) = listOf(File(root, HTTP_CACHE_DIR), File(root, ENGINE_CACHE_DIR))

/**
 * If the OS key is out of reach, the launch runs as a guest: signing in still works, but nothing
 * is written that a later launch could read — never the jar in the clear.
 */
internal suspend fun openCookieStore(store: AppStore, dataDir: File, log: Logger?): CookieStoreOpen = withContext(Dispatchers.IO) {
    val userKey = try {
        userScopeCookieKey(store, log)
    } catch (error: Throwable) {
        log?.log(Logger.Level.WARN, "OS cookie key unavailable, this launch runs as a guest", error)
        null
    }
    when {
        // A password-sealed jar never needs the OS key: every write after unlock is password-sealed.
        CookieVault.isPasswordSealed(store) ->
            CookieStoreOpen.Locked(CookieVault(store, userKey ?: throwawayKey()), store, dataDir, userKey)
        userKey == null -> CookieStoreOpen.Ready(guestSession())
        else -> CookieStoreOpen.Ready(userSession(store, dataDir, userKey))
    }
}

private fun userSession(store: AppStore, dataDir: File, userKey: SecretKey): CookieSession {
    val vault = CookieVault(store, userKey)
    return CookieSession(PersistentCookieJar(vault), vault, dataDir)
}

private fun guestSession(): CookieSession {
    val root = Files.createTempDirectory("ymusic-guest").toFile()
    Runtime.getRuntime().addShutdownHook(Thread { root.deleteRecursively() })
    return CookieSession(PersistentCookieJar(MemoryCookieDocument()), null, root)
}

/** A guest's jar: nothing of it outlives the process. */
private class MemoryCookieDocument : CookieDocument {
    @Volatile private var document: ByteString? = null

    override fun read(): ByteString? = document

    override fun write(document: ByteString) {
        this.document = document
    }

    override fun delete() {
        document = null
    }
}

private fun throwawayKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
