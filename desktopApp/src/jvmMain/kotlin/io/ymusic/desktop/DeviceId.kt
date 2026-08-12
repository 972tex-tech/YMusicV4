package io.ymusic.desktop

import kmpcommon.log.Logger
import kmpcommon.log.debug
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * A machine-scoped seed for the anonymous crash-reporting id — unlike a stored UUID it survives an
 * app-data wipe: macOS `IOPlatformUUID`, Windows `MachineGuid`, Linux `/etc/machine-id`. The raw
 * value is machine-wide (every app on the box sees the same one), so it is HASHED with an app salt
 * before use — stable for us, never correlatable with other apps' use of the same id. Null when the
 * platform probe fails (the caller falls back to a random UUID; the persisted copy keeps either
 * choice stable afterwards).
 */
internal fun machineSeededDeviceId(log: Logger? = null): String? = runCatching {
    val os = System.getProperty("os.name")?.lowercase().orEmpty()
    val raw = when {
        "mac" in os ->
            exec("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
                ?.lineSequence()
                ?.firstOrNull { "IOPlatformUUID" in it }
                ?.substringAfter("= \"", "")
                ?.substringBefore('"')
        "win" in os ->
            exec("reg", "query", """HKLM\SOFTWARE\Microsoft\Cryptography""", "/v", "MachineGuid")
                ?.lineSequence()
                ?.firstOrNull { "MachineGuid" in it }
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.lastOrNull()
        else ->
            sequenceOf("/etc/machine-id", "/var/lib/dbus/machine-id")
                .map(::File)
                .firstOrNull { it.canRead() }
                ?.readText()
                ?.trim()
    }
    raw?.takeIf { it.isNotBlank() }?.let { sha256Hex("ymusic:$it") }
}.onFailure { log?.debug { "machine-id probe failed, falling back to a random UUID: $it" } }
    .getOrNull()

private fun exec(vararg command: String): String? {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val out = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
    }
    return out.takeIf { process.exitValue() == 0 }
}

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { b ->
        ((b.toInt() and 0xFF) + 0x100).toString(16).substring(1)
    }
