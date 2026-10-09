package io.ymusic.desktop

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.IOException
import java.security.SecureRandom

/**
 * Generic-password items in the login keychain, over Security.framework via JNA.
 *
 * Deliberately the file-based keychain (no `kSecUseDataProtectionKeychain`): the data-protection
 * keychain needs a keychain-access-groups entitlement, i.e. a provisioning profile, which a
 * Developer ID jpackage build does not carry. Here the item's ACL trusts the creating app's code
 * signature, so another app reading it gets a user prompt instead of the bytes.
 */
internal object MacKeychain {

    private val cf = NativeLibrary.getInstance("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
    private val sec = NativeLibrary.getInstance("/System/Library/Frameworks/Security.framework/Security")

    private const val ERR_SEC_SUCCESS = 0
    private const val ERR_SEC_DUPLICATE_ITEM = -25299
    private const val ERR_SEC_ITEM_NOT_FOUND = -25300
    private const val CF_STRING_ENCODING_UTF8 = 0x08000100

    /** The secret stored under ([service], [account]), created as [size] random bytes on first use. */
    fun getOrCreateSecret(service: String, account: String, size: Int): ByteArray {
        find(service, account)?.let { return it }
        val secret = ByteArray(size).also { SecureRandom().nextBytes(it) }
        return when (val status = add(service, account, secret)) {
            ERR_SEC_SUCCESS -> secret
            // Another process of this app won the race: its secret is the one to use.
            ERR_SEC_DUPLICATE_ITEM -> find(service, account) ?: throw IOException("keychain item vanished after duplicate")
            else -> throw IOException("SecItemAdd failed: OSStatus $status")
        }
    }

    private fun find(service: String, account: String): ByteArray? = withQuery(service, account) { query ->
        dictSet(query, secConst("kSecReturnData"), cfConst("kCFBooleanTrue"))
        dictSet(query, secConst("kSecMatchLimit"), secConst("kSecMatchLimitOne"))
        val result = PointerByReference()
        when (val status = sec.getFunction("SecItemCopyMatching").invokeInt(arrayOf(query, result))) {
            ERR_SEC_SUCCESS -> {
                val data = result.value
                try {
                    val length = cf.getFunction("CFDataGetLength").invokeLong(arrayOf(data)).toInt()
                    cf.getFunction("CFDataGetBytePtr").invokePointer(arrayOf(data)).getByteArray(0, length)
                } finally {
                    release(data)
                }
            }
            ERR_SEC_ITEM_NOT_FOUND -> null
            else -> throw IOException("SecItemCopyMatching failed: OSStatus $status")
        }
    }

    private fun add(service: String, account: String, secret: ByteArray): Int = withQuery(service, account) { query ->
        val bytes = Memory(secret.size.toLong()).apply { write(0, secret, 0, secret.size) }
        val data = cf.getFunction("CFDataCreate").invokePointer(arrayOf(null, bytes, secret.size.toLong()))
        try {
            dictSet(query, secConst("kSecValueData"), data)
            sec.getFunction("SecItemAdd").invokeInt(arrayOf(query, null))
        } finally {
            release(data)
        }
    }

    private inline fun <T> withQuery(service: String, account: String, block: (Pointer) -> T): T {
        val query = cf.getFunction("CFDictionaryCreateMutable").invokePointer(
            arrayOf(
                null,
                0L,
                cf.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks"),
                cf.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks"),
            ),
        )
        val serviceRef = cfString(service)
        val accountRef = cfString(account)
        try {
            dictSet(query, secConst("kSecClass"), secConst("kSecClassGenericPassword"))
            dictSet(query, secConst("kSecAttrService"), serviceRef)
            dictSet(query, secConst("kSecAttrAccount"), accountRef)
            return block(query)
        } finally {
            release(accountRef)
            release(serviceRef)
            release(query)
        }
    }

    private fun cfString(value: String): Pointer =
        cf.getFunction("CFStringCreateWithCString").invokePointer(arrayOf<Any?>(null, value, CF_STRING_ENCODING_UTF8))

    private fun dictSet(dict: Pointer, key: Pointer, value: Pointer) {
        cf.getFunction("CFDictionarySetValue").invokeVoid(arrayOf(dict, key, value))
    }

    private fun release(ref: Pointer) {
        cf.getFunction("CFRelease").invokeVoid(arrayOf(ref))
    }

    private fun secConst(name: String): Pointer = sec.getGlobalVariableAddress(name).getPointer(0)

    private fun cfConst(name: String): Pointer = cf.getGlobalVariableAddress(name).getPointer(0)
}
