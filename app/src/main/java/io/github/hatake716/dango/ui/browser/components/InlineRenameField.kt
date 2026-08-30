package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.data.fs.NameUtils
import io.github.hatake716.dango.ui.theme.DangoTheme

/**
 * Finder 風のインラインリネーム（SPEC §6.3: 拡張子を除いた部分を初期選択）。
 * IME の完了で確定、フォーカス喪失でも確定する。
 */
@Composable
fun InlineRenameField(
    initialName: String,
    isDir: Boolean,
    textAlign: TextAlign,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DangoTheme.colors
    val focusRequester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    var committed by remember { mutableStateOf(false) }
    var value by remember {
        val (base, _) = NameUtils.splitExtension(initialName, isDir)
        mutableStateOf(
            TextFieldValue(text = initialName, selection = TextRange(0, base.length)),
        )
    }

    fun commit() {
        if (committed) return
        committed = true
        val name = value.text.trim()
        if (name.isEmpty() || name == initialName) onCancel() else onCommit(name)
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    BasicTextField(
        value = value,
        onValueChange = { value = it },
        textStyle = TextStyle(
            color = colors.textPrimary,
            fontSize = 12.sp,
            textAlign = textAlign,
        ),
        cursorBrush = SolidColor(colors.selectionFocused),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.windowBackground)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (state.isFocused) {
                    hadFocus = true
                } else if (hadFocus) {
                    commit()
                }
            },
    )
}
