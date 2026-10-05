package com.sshapp.ui

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Makes text fields inside [content] behave like a terminal for the soft keyboard: no autocorrect,
 * no word suggestions, no auto-capitalisation and no learning of typed text.
 *
 * `KeyboardOptions(autoCorrectEnabled = false)` is only a hint that Gboard and others largely ignore.
 * Declaring the field as a "visible password" is what keyboards reliably respect (Termux does the same).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoAutocorrect(
    /** Use a masked password field instead (e.g. at a `sudo` prompt). Toggling this keeps focus. */
    hidden: Boolean = false,
    content: @Composable () -> Unit,
) {
    val isHidden by rememberUpdatedState(hidden)
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val modified = PlatformTextInputMethodRequest { outAttributes ->
                val connection = request.createInputConnection(outAttributes)
                outAttributes.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    if (isHidden) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                connection
            }
            nextHandler.startInputMethod(modified)
        },
        content = content,
    )
}
