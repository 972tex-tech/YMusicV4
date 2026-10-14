package io.ymusic.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.downloads_sign_in_message
import io.ymusic.ui.resources.downloads_sign_in_title
import io.ymusic.ui.resources.downloads_unavailable_message
import io.ymusic.ui.resources.downloads_unavailable_title
import io.ymusic.ui.resources.menu_remove
import io.ymusic.ui.resources.signin_button
import org.jetbrains.compose.resources.stringResource

/** Retry on a sign-in-required failure: [onSignIn] opens the YouTube sign-in, which re-queues on success. */
@Composable
internal fun DownloadSignInDialog(onSignIn: () -> Unit, onDismiss: () -> Unit) {
    RetryResolveDialog(
        title = stringResource(Res.string.downloads_sign_in_title),
        message = stringResource(Res.string.downloads_sign_in_message),
        actionLabel = stringResource(Res.string.signin_button),
        onAction = onSignIn,
        onDismiss = onDismiss,
    )
}

/** Retry on an unavailable verdict: YouTube's answer won't change on a re-run, so offer removal instead. */
@Composable
internal fun DownloadUnavailableDialog(onRemove: () -> Unit, onDismiss: () -> Unit) {
    RetryResolveDialog(
        title = stringResource(Res.string.downloads_unavailable_title),
        message = stringResource(Res.string.downloads_unavailable_message),
        actionLabel = stringResource(Res.string.menu_remove),
        onAction = onRemove,
        onDismiss = onDismiss,
    )
}

@Composable
private fun RetryResolveDialog(title: String, message: String, actionLabel: String, onAction: () -> Unit, onDismiss: () -> Unit) {
    ElevatedBarDialog(
        title = title,
        onDismiss = onDismiss,
        canScrollBackward = false,
        canScrollForward = false,
        buttons = {
            DialogSecondaryButton(stringResource(Res.string.dialog_cancel), onClick = onDismiss)
            TextButton(onClick = {
                onDismiss()
                onAction()
            }) { Text(actionLabel, color = DesignColors.Accent) }
        },
    ) {
        Text(
            message,
            Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.Space24, vertical = DesignSpacing.Space16),
            style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink, lineHeight = 20.sp),
        )
    }
}
