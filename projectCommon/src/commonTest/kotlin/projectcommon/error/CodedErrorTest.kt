package projectcommon.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodedErrorTest {

    @Test
    fun deepest_code_in_the_cause_chain_wins() {
        val root = TechnicalException("E10001", "root cause")
        val mid = TechnicalException("E20002", "wrapped", cause = root)
        val outer = RuntimeException("outermost", mid)
        assertEquals("E10001", outer.errorCode())
    }

    @Test
    fun uncoded_chain_has_no_code() {
        assertNull(RuntimeException("plain", IllegalStateException("also plain")).errorCode())
    }

    @Test
    fun code_and_user_message_survive_wrapping() {
        val server = TechnicalException("E90210", "maintenance window", userMessage = "Bảo trì đến 15:00")
        val wrapped = RuntimeException("call failed", server)
        val coded = wrapped.codedError()!!
        assertEquals("E90210", coded.code)
        assertEquals("Bảo trì đến 15:00", coded.userMessage)
    }

    @Test
    fun technical_exception_message_carries_the_code_for_logs() {
        assertEquals("[E12375] frame malformed", TechnicalException("E12375", "frame malformed").message)
    }
}
