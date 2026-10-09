package io.ymusic.desktop

import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Sink
import okio.Source
import okio.buffer
import okio.cipherSink
import okio.cipherSource
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Files written through this are AES-256-GCM under [key]: a random 12-byte nonce, then the
 * ciphertext with its tag. A file that does not authenticate (wrong key, tampered, plaintext)
 * fails its read with an exception rather than yielding bytes. Append is unsupported — GCM
 * seals a whole file.
 */
internal class EncryptedFileSystem(delegate: FileSystem, private val key: SecretKey) : ForwardingFileSystem(delegate) {

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        }
        val out = super.sink(file, mustCreate).buffer()
        out.write(nonce)
        return out.cipherSink(cipher)
    }

    override fun source(file: Path): Source {
        val input = super.source(file).buffer()
        val nonce = try {
            input.readByteArray(NONCE_BYTES.toLong())
        } catch (error: Throwable) {
            input.close()
            throw error
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        }
        return input.cipherSource(cipher)
    }

    override fun appendingSink(file: Path, mustExist: Boolean): Sink =
        throw UnsupportedOperationException("append to an AES-GCM file")

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
