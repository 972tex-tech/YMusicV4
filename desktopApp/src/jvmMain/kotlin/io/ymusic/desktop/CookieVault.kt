package io.ymusic.desktop

import io.ymusic.remoteconfig.CookieDocument
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.IOException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cookie jar's [CookieDocument]: the document is AES-256-GCM sealed as
 * `header | nonce | ciphertext+tag`, the header bound in as AAD, and kept as the [COOKIE_JAR] row of
 * [store]. The header names the key: `YMC1U` for [userKey] (the OS user's key — see
 * [userScopeCookieKey]), or `YMC1P` + iterations + salt for a key derived from the user's password.
 * A document that does not authenticate fails its read.
 *
 * Writes and [protect] share one lock, so a write that raced a [protect] can never leave the jar
 * under the weaker key.
 */
internal class CookieVault(private val store: AppStore, private val userKey: SecretKey) : CookieDocument {

    class PasswordSeal internal constructor(val iterations: Int, val salt: ByteString, internal val key: SecretKey) {
        val header: ByteString = Buffer().write(MAGIC).writeByte(MODE_PASSWORD.toInt()).writeInt(iterations).write(salt).readByteString()
    }

    private val lock = Any()

    @Volatile private var password: PasswordSeal? = null

    val isPasswordProtected: Boolean get() = password != null

    override fun read(): ByteString? {
        val sealed = store.secret(COOKIE_JAR) ?: return null
        val seal = password
        return open(sealed) { header ->
            when (header) {
                USER_HEADER -> userKey
                seal?.header -> seal?.key
                else -> null
            }
        }
    }

    override fun write(document: ByteString) = synchronized(lock) {
        store.putSecret(COOKIE_JAR, seal(document))
    }

    override fun delete() = synchronized(lock) {
        store.deleteSecret(COOKIE_JAR)
    }

    /** Every later write, and the stored jar now, goes under [seal]. */
    fun protect(seal: PasswordSeal) = synchronized(lock) {
        val document = read()
        password = seal
        if (document != null) store.putSecret(COOKIE_JAR, seal(document))
    }

    /**
     * Opens the password-sealed jar with [password] and keeps that key for every later read and
     * write. False when the password does not authenticate it.
     */
    fun unlock(password: CharArray): Boolean {
        val sealed = store.secret(COOKIE_JAR) ?: throw IOException("no cookie store to unlock")
        val seal = passwordSealOf(readHeader(Buffer().write(sealed)), password)
        try {
            open(sealed) { if (it == seal.header) seal.key else null }
        } catch (error: Throwable) {
            if (error.isAuthFailure()) return false
            throw error
        }
        this.password = seal
        return true
    }

    private fun seal(document: ByteString): ByteArray {
        val seal = password
        val header = seal?.header ?: USER_HEADER
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, seal?.key ?: userKey, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header.toByteArray())
        }
        return Buffer().write(header).write(nonce).write(cipher.doFinal(document.toByteArray())).readByteArray()
    }

    private fun open(sealed: ByteArray, keyFor: (ByteString) -> SecretKey?): ByteString {
        val input = Buffer().write(sealed)
        val header = readHeader(input)
        val key = keyFor(header) ?: throw IOException("cookie store is sealed under another key")
        val nonce = input.readByteArray(NONCE_BYTES.toLong())
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header.toByteArray())
        }
        return cipher.doFinal(input.readByteArray()).toByteString()
    }

    companion object {
        private val MAGIC = "YMC1".encodeUtf8()
        private const val MODE_USER: Byte = 'U'.code.toByte()
        private const val MODE_PASSWORD: Byte = 'P'.code.toByte()
        private val USER_HEADER = Buffer().write(MAGIC).writeByte(MODE_USER.toInt()).readByteString()
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val SALT_BYTES = 16

        /** OWASP's 2023 floor for PBKDF2-HMAC-SHA256. */
        private const val ITERATIONS = 600_000
        private val random = SecureRandom()

        /** True when [store] holds a password-sealed jar — readable without any key. */
        fun isPasswordSealed(store: AppStore): Boolean {
            val sealed = store.secret(COOKIE_JAR)?.toByteString() ?: return false
            return sealed.size > MAGIC.size && sealed.startsWith(MAGIC) && sealed[MAGIC.size] == MODE_PASSWORD
        }

        /** A fresh seal for [password]. Deliberately slow (PBKDF2): call it off the main thread. */
        fun newPasswordSeal(password: CharArray): PasswordSeal {
            val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
            return PasswordSeal(ITERATIONS, ByteString.of(*salt), deriveKey(password, salt, ITERATIONS))
        }

        private fun passwordSealOf(header: ByteString, password: CharArray): PasswordSeal {
            if (header[MAGIC.size] != MODE_PASSWORD) throw IOException("cookie store is not password-sealed")
            val fields = Buffer().write(header, MAGIC.size + 1, header.size - MAGIC.size - 1)
            val iterations = fields.readInt()
            val salt = fields.readByteString()
            return PasswordSeal(iterations, salt, deriveKey(password, salt.toByteArray(), iterations))
        }

        private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKey {
            val spec = PBEKeySpec(password, salt, iterations, 256)
            try {
                val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                return SecretKeySpec(bytes, "AES")
            } finally {
                spec.clearPassword()
            }
        }

        private fun readHeader(input: Buffer): ByteString {
            val head = input.readByteString(MAGIC.size + 1L)
            if (!head.startsWith(MAGIC)) throw IOException("not a sealed cookie file")
            return when (head[MAGIC.size]) {
                MODE_USER -> head
                MODE_PASSWORD -> Buffer().write(head).write(input.readByteString(4L + SALT_BYTES)).readByteString()
                else -> throw IOException("unknown cookie seal ${head[MAGIC.size]}")
            }
        }

        private fun Throwable.isAuthFailure(): Boolean =
            generateSequence(this) { it.cause }.any { it is AEADBadTagException }
    }
}
