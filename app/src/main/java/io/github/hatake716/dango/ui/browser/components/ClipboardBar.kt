package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.domain.model.ClipboardMode
import io.github.hatake716.dango.domain.model.ClipboardState
import io.github.hatake716.dango.ui.theme.DangoTheme

/** 内部クリップボードの状態表示と貼り付け操作（SPEC §6.3） */
@Composable
fun ClipboardBar(
    clipboard: ClipboardState,
    enabled: Boolean,
    onPaste: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = DangoTheme.colors
    val isMove = clipboard.mode == ClipboardMode.MOVE
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.sidebar)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                if (isMove) R.string.clip_bar_move else R.string.clip_bar_copy,
                clipboard.entries.size,
            ),
            color = colors.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onPaste, enabled = enabled) {
            Text(
                text = stringResource(if (isMove) R.string.clip_move_here else R.string.clip_paste),
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    colors.textSecondary.copy(alpha = 0.4f)
                },
                fontSize = 12.sp,
            )
        }
        IconButton(onClick = onClear) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.cd_clear_clipboard),
                tint = colors.textSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
    HorizontalDivider(color = colors.divider)
}
