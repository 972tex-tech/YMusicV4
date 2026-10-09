package io.ymusic.desktop

import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Sink
import okio.Source
import okio.blackholeSink
import okio.buffer
import okio.cipherSink
import okio.cipherSource
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cookie jar's file system: every file written through it is AES-256-GCM sealed as
 * `header | nonce | ciphertext+tag`, the header bound in as AAD. The header names the key: `YMC1U`
 * for [userKey] (the OS user's key — see [userScopeCookieKey]), or `YMC1P` + iterations + salt for
 * a key derived from the user's password. A file that does not authenticate fails its read.
 *
 * Every file moved into place is re-sealed under the current key first, so a write that raced a
 * [protect] can never leave the jar under the weaker key.
 */
internal class CookieVault(delegate: FileSystem, private val userKey: SecretKey) : ForwardingFileSystem(delegate) {

    class PasswordSeal internal constructor(val iterations: Int, val salt: ByteString, internal val key: SecretKey) {
        val header: ByteString = Buffer().write(MAGIC).writeByte(MODE_PASSWORD.toInt()).writeInt(iterations).write(salt).readByteString()
    }

    private val lock = Any()

    @Volatile private var password: PasswordSeal? = null

    val isPasswordProtected: Boolean get() = password != null

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        val seal = password
        val header = seal?.header ?: USER_HEADER
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, seal?.key ?: userKey, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header.toByteArray())
        }
        val out = super.sink(file, mustCreate).buffer()
        out.write(header).write(nonce)
        return out.cipherSink(cipher)
    }

    override fun source(file: Path): Source = open(file) { header ->
        val seal = password
        when (header) {
            USER_HEADER -> userKey
            seal?.header -> seal?.key
            else -> null
        }
    }

    override fun appendingSink(file: Path, mustExist: Boolean): Sink =
        throw UnsupportedOperationException("append to an AES-GCM file")

    override fun atomicMove(source: Path, target: Path) = synchronized(lock) {
        resealLocked(source)
        super.atomicMove(source, target)
    }

    /** Every later write, and the sealed file at [path] now, goes under [seal]. */
    fun protect(path: Path, seal: PasswordSeal) = synchronized(lock) {
        password = seal
        if (exists(path)) resealLocked(path)
    }

    /**
     * Opens the password-sealed [path] with [password] and keeps that key for every later read and
     * write. False when the password does not authenticate the file.
     */
    fun unlock(path: Path, password: CharArray): Boolean {
        val header = delegate.source(path).buffer().use(::readHeader)
        val seal = passwordSealOf(header, password)
        try {
            open(path) { if (it == seal.header) seal.key else null }.buffer().use { it.readAll(blackholeSink()) }
        } catch (error: Throwable) {
            if (error.isAuthFailure()) return false
            throw error
        }
        this.password = seal
        return true
    }

    private fun open(file: Path, keyFor: (ByteString) -> SecretKey?): Source {
        val input = super.source(file).buffer()
        try {
            val header = readHeader(input)
            val key = keyFor(header) ?: throw IOException("cookie store is sealed under another key")
            val nonce = input.readByteArray(NONCE_BYTES.toLong())
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(header.toByteArray())
            }
            return input.cipherSource(cipher)
        } catch (error: Throwable) {
            input.close()
            throw error
        }
    }

    private fun resealLocked(path: Path) {
        val current = password?.header ?: USER_HEADER
        if (delegate.source(path).buffer().use(::readHeader) == current) return
        val tmp = requireNotNull(path.parent) { "no parent: $path" } / (path.name + ".reseal")
        sink(tmp).buffer().use { out -> source(path).use { out.writeAll(it) } }
        super.atomicMove(tmp, path)
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

        /** True when [path] holds a password-sealed jar — readable without any key. */
        fun isPasswordSealed(fileSystem: FileSystem, path: Path): Boolean {
            if (!fileSystem.exists(path)) return false
            return fileSystem.source(path).buffer().use { input ->
                input.request(MAGIC.size + 1L) &&
                    input.buffer.rangeEquals(0, MAGIC) &&
                    input.buffer[MAGIC.size.toLong()] == MODE_PASSWORD
            }
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

        private fun readHeader(input: BufferedSource): ByteString {
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
