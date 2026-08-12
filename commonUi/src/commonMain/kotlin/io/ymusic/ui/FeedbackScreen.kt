package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_remove_attachment
import io.ymusic.ui.resources.feedback_attach
import io.ymusic.ui.resources.feedback_email_label
import io.ymusic.ui.resources.feedback_file_too_large
import io.ymusic.ui.resources.feedback_hint
import io.ymusic.ui.resources.feedback_message_label
import io.ymusic.ui.resources.feedback_name_label
import io.ymusic.ui.resources.feedback_send
import io.ymusic.ui.resources.feedback_send_failed
import io.ymusic.ui.resources.feedback_sent
import kmpcommon.log.Logger
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** The Feedback section's draft, held at the app root so a half-written report survives a section
 *  switch. [clearReport] runs after a successful send — name/email stay (they identify the sender,
 *  not the report). */
internal class FeedbackSession(parentLog: Logger?) {
    internal val log = parentLog?.fork("Feedback")
    var message by mutableStateOf("")
    var name by mutableStateOf("")
    var email by mutableStateOf("")
    val attachments = mutableStateListOf<FeedbackFile>()
    var sending by mutableStateOf(false)

    fun clearReport() {
        message = ""
        attachments.clear()
    }
}

/**
 * The drawer's Feedback section: a free-text report with optional name/email and file attachments,
 * delivered through [AppGraph.sendFeedback] (Sentry user feedback in the shells). The shell's app
 * bar provides the section chrome; attach is hidden when the shell provides no picker.
 */
@Composable
internal fun FeedbackScreen(graph: AppGraph, session: FeedbackSession) {
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val scrollState = rememberScrollState()
    TrackScroll { scrollState.canScrollBackward }
    // Large windows: the column caps at FormMaxWidth and centers both ways — full-bleed fields on
    // an expanded window read as ribbons, and a short form pinned to the top of a tall window
    // leaves a dead lower half. The min-height keeps the centering inside the scroll content, so
    // an overflowing form (many attachments, small window) still just scrolls from the top.
    // Margin follows the window class like the grids do.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margin = gridMetricsFor(maxWidth).margin
        val viewportHeight = maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .widthIn(max = DesignSpacing.FormMaxWidth)
                    .fillMaxWidth()
                    .heightIn(min = viewportHeight)
                    .padding(horizontal = margin, vertical = DesignSpacing.Space16),
                verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space16, Alignment.CenterVertically),
            ) {
                Text(
                    stringResource(Res.string.feedback_hint),
                    style = TextStyle(fontSize = 13.sp, color = DesignColors.InkMuted),
                )
                AppTextField(
                    value = session.message,
                    onValueChange = { session.message = it },
                    label = stringResource(Res.string.feedback_message_label),
                    singleLine = false,
                )
                AppTextField(
                    value = session.name,
                    onValueChange = { session.name = it },
                    label = stringResource(Res.string.feedback_name_label),
                )
                AppTextField(
                    value = session.email,
                    onValueChange = { session.email = it },
                    label = stringResource(Res.string.feedback_email_label),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                for (file in session.attachments) {
                    AttachmentRow(file, onRemove = { session.attachments.remove(file) })
                }
                val picker = graph.pickFeedbackFiles
                if (picker != null) {
                    TextButton(
                        enabled = !session.sending,
                        onClick = {
                            scope.launch {
                                try {
                                    for (file in picker()) {
                                        if (file.bytes == null) {
                                            snackbar(getString(Res.string.feedback_file_too_large, file.filename))
                                        } else {
                                            session.attachments.add(file)
                                        }
                                    }
                                } catch (c: kotlinx.coroutines.CancellationException) {
                                    throw c
                                } catch (e: Exception) {
                                    session.log?.captureError(e, "attachment pick failed")
                                }
                            }
                        },
                    ) {
                        Text(stringResource(Res.string.feedback_attach), color = DesignColors.Accent)
                    }
                }
                SendButton(session, enabled = session.message.isNotBlank() && !session.sending) {
                    scope.launch {
                        session.sending = true
                        try {
                            graph.sendFeedback?.invoke(
                                FeedbackDraft(
                                    message = session.message.trim(),
                                    name = session.name.trim().ifEmpty { null },
                                    email = session.email.trim().ifEmpty { null },
                                    attachments = session.attachments.filter { it.bytes != null },
                                ),
                            )
                            snackbar(getString(Res.string.feedback_sent))
                            session.clearReport()
                        } catch (c: kotlinx.coroutines.CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            session.log?.captureError(e, "feedback send failed")
                            snackbar(getString(Res.string.feedback_send_failed))
                        } finally {
                            session.sending = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentRow(file: FeedbackFile, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            file.filename,
            style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(sizeLabel(file.sizeBytes), style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary))
        ActionIcon(Icons.Default.Close, stringResource(Res.string.cd_remove_attachment), onClick = onRemove)
    }
}

/** The accent primary button, mirroring the sign-in preface's; a spinner replaces the label mid-send. */
@Composable
private fun SendButton(session: FeedbackSession, enabled: Boolean, onSend: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                if (enabled || session.sending) DesignColors.Accent else DesignColors.Accent.copy(alpha = 0.4f),
                RoundedCornerShape(8.dp),
            )
            .clickable(enabled = enabled, onClick = onSend)
            .padding(vertical = DesignSpacing.Space16),
        contentAlignment = Alignment.Center,
    ) {
        if (session.sending) {
            CircularProgressIndicator(Modifier.size(18.dp), color = DesignColors.OnInk, strokeWidth = 2.dp)
        } else {
            Text(
                stringResource(Res.string.feedback_send).uppercase(),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.OnInk),
            )
        }
    }
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1_048_576 -> "${(bytes * 10 / 1_048_576) / 10.0} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
