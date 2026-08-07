package ymusic.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color

/**
 * The app's universal text input: no background box — just a floating label (sits like a placeholder
 * when empty, then floats up and turns [DesignColors.Accent] on focus) over a single bottom underline
 * (grey → accent on focus). [supportingText] shows under the field, in the error colour when
 * [isError]. Wraps Material's TextField with a transparent container, re-coloured to the app's palette.
 */
@Composable
internal fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    autoFocus: Boolean = false,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val focusRequester = remember { FocusRequester() }
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().focusRequester(focusRequester),
        label = { Text(label) },
        singleLine = singleLine,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        colors = TextFieldDefaults.colors(
            // No fill — the field is just a floating label over the bottom underline.
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            errorContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            cursorColor = DesignColors.Accent,
            errorCursorColor = MaterialTheme.colorScheme.error,
            focusedIndicatorColor = DesignColors.Accent,
            unfocusedIndicatorColor = DesignColors.OutlineSoft,
            errorIndicatorColor = MaterialTheme.colorScheme.error,
            focusedLabelColor = DesignColors.Accent,
            unfocusedLabelColor = DesignColors.InkSecondary,
            errorLabelColor = MaterialTheme.colorScheme.error,
            focusedTextColor = DesignColors.Ink,
            unfocusedTextColor = DesignColors.Ink,
        ),
    )
    if (autoFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}
