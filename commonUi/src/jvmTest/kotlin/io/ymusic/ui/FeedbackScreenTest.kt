package io.ymusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.unit.dp
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.embeddedEngineJsSource
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The drawer's Feedback section: composing a report with attachments hands ONE [FeedbackDraft] to
 *  the shell's sender; an over-limit pick is refused with a message instead of attached. */
@OptIn(ExperimentalTestApi::class)
class FeedbackScreenTest {

    private fun engines() =
        DynamicEngineManager(CannedHttp { _, _ -> throw UnsupportedOperationException() }, appVersion = 1, jsSource = embeddedEngineJsSource())

    @Test
    fun composing_and_sending_a_report_delivers_one_draft() {
        Locale.setDefault(Locale.ENGLISH)
        var sent: FeedbackDraft? = null
        val snackbars = mutableListOf<String>()
        val session = FeedbackSession(parentLog = null)
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
                    FeedbackScreen(graph, session)
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
            waitUntil(timeoutMillis = 5_000) { sent != null }
            val draft = sent!!
            assertEquals("The app broke", draft.message)
            assertEquals(null, draft.name)
            assertEquals("jane@example.com", draft.email)
            assertEquals(listOf("log.txt"), draft.attachments.map { it.filename })
            assertTrue(snackbars.any { "thank you" in it }, "sent confirmation expected: $snackbars")
            // The report resets for a fresh one; the sender identity stays.
            waitUntil(timeoutMillis = 5_000) { session.message.isEmpty() && session.attachments.isEmpty() }
            assertEquals("jane@example.com", session.email)
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
                    FeedbackScreen(graph, FeedbackSession(parentLog = null))
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
    fun a_large_window_caps_the_form_and_centers_it_both_ways() {
        Locale.setDefault(Locale.ENGLISH)
        val graph = AppGraph(
            engines = engines(),
            player = StubPlayer(),
            playerSurface = {},
            sendFeedback = {},
        )
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalSnackbar provides {}) {
                    FeedbackScreen(graph, FeedbackSession(parentLog = null))
                }
            }
            val root = onRoot().getBoundsInRoot()
            val send = onNodeWithText("SEND").getBoundsInRoot()
            val rootWidth = root.right - root.left
            val sendWidth = send.right - send.left
            assertTrue(rootWidth > DesignSpacing.FormMaxWidth, "test surface should be wider than the cap ($rootWidth)")
            assertTrue(sendWidth <= DesignSpacing.FormMaxWidth, "form should cap at FormMaxWidth, was $sendWidth")
            val leftGap = send.left
            val rightGap = rootWidth - send.right
            assertTrue((leftGap - rightGap).value < 2f, "form should center: left=$leftGap right=$rightGap")

            // Vertically centered too (both dimensions are large here): the gap above the first
            // content (the hint) matches the gap below the last (the send button).
            val hint = onNodeWithText("Tell us", substring = true).getBoundsInRoot()
            val rootHeight = root.bottom - root.top
            val topGap = hint.top
            val bottomGap = rootHeight - send.bottom
            assertTrue(topGap.value > 50f, "a short form on a tall surface should not pin to the top (topGap=$topGap)")
            assertTrue((topGap - bottomGap).value < 2f, "form should center vertically: top=$topGap bottom=$bottomGap")
        }
    }

    @Test
    fun a_compact_width_window_keeps_the_form_top_anchored() {
        Locale.setDefault(Locale.ENGLISH)
        val graph = AppGraph(
            engines = engines(),
            player = StubPlayer(),
            playerSurface = {},
            sendFeedback = {},
        )
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalSnackbar provides {}) {
                    // A tall phone-shaped viewport: large height but compact width → no centering.
                    Box(Modifier.size(400.dp, 700.dp)) {
                        FeedbackScreen(graph, FeedbackSession(parentLog = null))
                    }
                }
            }
            val hint = onNodeWithText("Tell us", substring = true).getBoundsInRoot()
            assertTrue(hint.top.value < 50f, "compact width should stay top-anchored (topGap=${hint.top})")
        }
    }

    @Test
    fun feedback_section_shows_the_form_only_when_the_shell_can_send() {
        Locale.setDefault(Locale.ENGLISH)
        val engines = engines()
        val withSender = AppGraph(
            engines = engines,
            player = StubPlayer(),
            playerSurface = {},
            sendFeedback = {},
        )
        runComposeUiTest {
            setContent { App(withSender, mutableStateOf(AppSection.Feedback)) }
            waitUntilAtLeastOneExists(hasText("What happened?"), 20_000)
        }
        val withoutSender = AppGraph(
            engines = engines,
            player = StubPlayer(),
            playerSurface = {},
        )
        runComposeUiTest {
            setContent { App(withoutSender, mutableStateOf(AppSection.Feedback)) }
            // No sender → the section still exists but only announces itself as coming later.
            waitUntilAtLeastOneExists(hasText("coming", substring = true), 20_000)
            onNodeWithText("What happened?").assertDoesNotExist()
        }
    }
}
