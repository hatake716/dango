package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.domain.model.ClipboardMode
import io.github.hatake716.dango.domain.model.ClipboardState
import io.github.hatake716.dango.ui.theme.DangoTheme

/** 内部クリップボードの状態表示と貼り付け操作（SPEC §6.3）。Finder の細い情報バーの体裁 */
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
            .height(32.dp)
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        FinderPushButton(
            text = stringResource(if (isMove) R.string.clip_move_here else R.string.clip_paste),
            onClick = onPaste,
            style = FinderButtonStyle.Default,
            enabled = enabled,
            compact = true,
        )
        val clearLabel = stringResource(R.string.cd_clear_clipboard)
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = clearLabel, onClick = onClear),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Cancel,
                contentDescription = clearLabel,
                tint = colors.textSecondary.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = colors.divider)
}
