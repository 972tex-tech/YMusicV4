package kmpffmpeg

import kotlinx.coroutines.test.runTest
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KmpProcessBuilder documents "the child runs with exactly these — there is no inheritance of the
 * parent environment", and the posix backend implements that. The JVM backend used
 * ProcessBuilder.environment().putAll(...), which starts as a COPY of the parent's environment, so
 * the child silently inherited PATH/LD_LIBRARY_PATH/proxy vars — a JVM-vs-native behavior split.
 */
class JvmProcessEnvTest {

    private val envBinary = listOf("/usr/bin/env", "/bin/env").firstOrNull { java.io.File(it).canExecute() }

    @Test
    fun child_environment_is_exactly_what_was_set_no_inheritance() = runTest {
        val env = envBinary ?: return@runTest // no `env` on this host — skip
        // A var that is surely present in THIS process's environment; the child must NOT see it.
        val parentOnly = System.getenv().keys.firstOrNull { it != "KMPFF_TEST" } ?: "PATH"

        val process = KmpProcessBuilder(env)
            .setEnv("KMPFF_TEST", "yes")
            .start()
        val output = process.stdout.buffer().readUtf8()
        process.exitCode.await()

        val keys = output.lineSequence().filter { it.isNotEmpty() }.map { it.substringBefore('=') }.toSet()
        assertTrue("KMPFF_TEST" in keys, "our own var must be present: $keys")
        assertTrue(parentOnly !in keys, "parent var '$parentOnly' must NOT be inherited: $keys")
        assertEquals(setOf("KMPFF_TEST"), keys, "child env must be exactly what was set")
    }
}
