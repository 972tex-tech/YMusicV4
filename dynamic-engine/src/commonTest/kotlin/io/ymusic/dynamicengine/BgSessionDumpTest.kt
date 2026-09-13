package io.ymusic.dynamicengine

import io.ymusic.dynamicengine.internal.writeBgSessionDump
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BgSessionDumpTest {
    @Test
    fun cache_json_writes_entropy_without_truncation() {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-bg-session-dump-test"
        fs.deleteRecursively(root)
        val entropy = (1..400).joinToString("\n") { "p 2233.$it" } + "\n"
        val json =
            """{"sessionKey":"k1","eventId":"ev","program":"PROG","snapshot":"SNAP",""" +
                """"integrityToken":"ITOK","entropy":${jsonString(entropy)},"pot":"POT",""" +
                """"sabrUrl":"https://example/videoplayback","sabrBody":"YQ"}"""
        val dir = writeBgSessionDump(fs, root, json, "k1")
        assertEquals("PROG", fs.read(dir / "program.txt") { readUtf8() })
        assertEquals(entropy, fs.read(dir / "entropy.txt") { readUtf8() })
        assertTrue(fs.read(dir / "entropy.txt") { readUtf8() }.lines().size > 100)
        assertEquals("k1\n", fs.read(root / "latest") { readUtf8() })
        fs.deleteRecursively(root)
    }

    private fun jsonString(s: String): String {
        val b = StringBuilder(s.length + 2)
        b.append('"')
        for (c in s) {
            when (c) {
                '\\' -> b.append("\\\\")
                '"' -> b.append("\\\"")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                else -> b.append(c)
            }
        }
        b.append('"')
        return b.toString()
    }
}
