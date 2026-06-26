package kmphttp.internal.publicsuffix

import kmphttp.internal.idn.Idna
import kotlin.test.Test
import kotlin.test.fail

/**
 * Test suite that reads public_suffix_list.dat and verifies each entry
 * is correctly stored in the DAFSA.
 */
class PslDataTest {

    @Test
    fun testAllPslEntriesInDafsa() {
        val pslData = readPslFile()
        if (pslData.isEmpty()) {
            println("WARNING: Could not read PSL file, skipping test")
            return
        }

        var passed = 0
        var failed = 0
        val failures = mutableListOf<String>()

        for (line in pslData.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//")) continue

            val (domain, expectedType) = when {
                trimmed.startsWith("!") -> trimmed.substring(1) to RuleType.EXCEPTION
                trimmed.startsWith("*.") -> trimmed.substring(2) to RuleType.WILDCARD
                else -> trimmed to RuleType.NORMAL
            }

            val result = lookupStringInDafsa(PSL_DAFSA, checkNotNull(Idna.toAscii(domain)))

            val success = when (expectedType) {
                RuleType.NORMAL -> result != DAFSA_NOT_FOUND && (result and DAFSA_EXCEPTION_RULE) == 0
                RuleType.WILDCARD -> result != DAFSA_NOT_FOUND && (result and DAFSA_WILDCARD_RULE) != 0
                RuleType.EXCEPTION -> result != DAFSA_NOT_FOUND && (result and DAFSA_EXCEPTION_RULE) != 0
            }

            if (success) {
                passed++
            } else {
                failed++
                if (failures.size < 20) {
                    failures.add("$trimmed -> result=$result expected=$expectedType")
                }
            }
        }

        println("PSL verification: $passed passed, $failed failed")
        if (failures.isNotEmpty()) {
            println("Sample failures:")
            failures.forEach { println("  $it") }
        }

        if (failed > 0) {
            fail("$failed PSL entries failed verification. See output for details.")
        }
    }

    private enum class RuleType { NORMAL, WILDCARD, EXCEPTION }
}
