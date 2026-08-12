package io.ymusic.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import io.ymusic.dynamicengine.DynamicEngineManager
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The feedback form: composing a report with attachments hands ONE [FeedbackDraft] to the shell's
 *  sender; an over-limit pick is refused with a message instead of attached. */
@OptIn(ExperimentalTestApi::class)
class FeedbackScreenTest {

    private fun engines() =
        DynamicEngineManager(CannedHttp { _, _ -> throw UnsupportedOperationException() }, appVersion = 1)

    @Test
    fun composing_and_sending_a_report_delivers_one_draft() {
        Locale.setDefault(Locale.ENGLISH)
        var sent: FeedbackDraft? = null
        val snackbars = mutableListOf<String>()
        var closed = false
        val graph = AppGraph(
            engines = engines(),
            player = StubPlayer(),
            playerSurface = {},
            sendFeedback = { sent = it },
            pickFeedbackFiles = {
                listOf(
                    FeedbackFile("log.txt", bytes = "hello".encodeToByteArray()),
                    // Over the cap: name-only, must be refused with a message.
                    FeedbackFile("huge.bin", bytes = null, sizeBytes = FeedbackFile.MAX_BYTES + 1),
                )
            },
        )
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalSnackbar provides { snackbars.add(it) }) {
                    FeedbackScreen(graph, FeedbackSession(parentLog = null), onBack = {}, onSent = { closed = true })
                }
            }
            // Fields in declaration order: message, name, email.
            onAllNodes(hasSetTextAction())[0].performTextInput("The app broke")
            onAllNodes(hasSetTextAction())[2].performTextInput("jane@example.com")

            onNodeWithText("Attach files").performClick()
            waitUntilAtLeastOneExists(hasText("log.txt"), 5_000)
            onNodeWithText("huge.bin").assertDoesNotExist()
            assertTrue(snackbars.any { "huge.bin" in it }, "oversized pick should be refused with a message: $snackbars")

            onNodeWithText("SEND").performClick()
            waitUntil(timeoutMillis = 5_000) { sent != null && closed }
            val draft = sent!!
            assertEquals("The app broke", draft.message)
            assertEquals(null, draft.name)
            assertEquals("jane@example.com", draft.email)
            assertEquals(listOf("log.txt"), draft.attachments.map { it.filename })
            assertTrue(snackbars.any { "thank you" in it }, "sent confirmation expected: $snackbars")
        }
    }

    @Test
    fun removing_an_attachment_keeps_it_out_of_the_draft() {
        Locale.setDefault(Locale.ENGLISH)
        var sent: FeedbackDraft? = null
        val graph = AppGraph(
            engines = engines(),
            player = StubPlayer(),
            playerSurface = {},
            sendFeedback = { sent = it },
            pickFeedbackFiles = { listOf(FeedbackFile("shot.png", bytes = byteArrayOf(1, 2, 3))) },
        )
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalSnackbar provides {}) {
                    FeedbackScreen(graph, FeedbackSession(parentLog = null), onBack = {}, onSent = {})
                }
            }
            onAllNodes(hasSetTextAction())[0].performTextInput("Report")
            onNodeWithText("Attach files").performClick()
            waitUntilAtLeastOneExists(hasText("shot.png"), 5_000)
            onNodeWithContentDescription("Remove attachment").performClick()
            waitUntilDoesNotExist(hasText("shot.png"), timeoutMillis = 5_000)

            onNodeWithText("SEND").performClick()
            waitUntil(timeoutMillis = 5_000) { sent != null }
            assertEquals(emptyList(), sent!!.attachments)
        }
    }

    @Test
    fun settings_offers_send_feedback_only_when_the_shell_can_send() {
        Locale.setDefault(Locale.ENGLISH)
        val engines = engines()
        val withSender = AppGraph(
            engines = engines,
            player = StubPlayer(),
            playerSurface = {},
            sendFeedback = {},
        )
        runComposeUiTest {
            setContent { App(withSender, mutableStateOf(AppSection.Settings)) }
            waitUntilAtLeastOneExists(hasText("Send feedback"), 20_000)
            onNodeWithText("Send feedback").performClick()
            // The feedback layer opened over Settings.
            waitUntilAtLeastOneExists(hasText("What happened?"), 5_000)
        }
        val withoutSender = AppGraph(
            engines = engines,
            player = StubPlayer(),
            playerSurface = {},
        )
        runComposeUiTest {
            setContent { App(withoutSender, mutableStateOf(AppSection.Settings)) }
            waitUntilAtLeastOneExists(hasText("Theme"), 20_000)
            onNodeWithText("Send feedback").assertDoesNotExist()
        }
    }
}
