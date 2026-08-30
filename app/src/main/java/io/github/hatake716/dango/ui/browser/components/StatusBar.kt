package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.transfer.TransferProgress
import io.github.hatake716.dango.ui.theme.DangoTheme
import io.github.hatake716.dango.ui.util.formatSize

/** ステータスバー（SPEC §4.5: N項目, 空き容量。転送中は進捗バーに切り替わる） */
@Composable
fun StatusBar(
    itemCount: Int,
    selectedCount: Int,
    freeSpaceBytes: Long?,
    transfer: TransferProgress?,
    onCancelTransfer: () -> Unit,
) {
    val colors = DangoTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.toolbar)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(26.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (transfer != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        if (transfer.isMove) R.string.transfer_status_move else R.string.transfer_status_copy,
                    ),
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.width(8.dp))
                LinearProgressIndicator(
                    progress = { transfer.fraction },
                    modifier = Modifier.weight(1f),
                    color = colors.selectionFocused,
                    trackColor = colors.selectionUnfocused,
                )
                IconButton(onClick = onCancelTransfer, modifier = Modifier.size(22.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.transfer_cancel),
                        tint = colors.textSecondary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        } else {
            val countText = if (selectedCount > 0) {
                stringResource(R.string.status_selected, selectedCount, itemCount)
            } else {
                stringResource(R.string.status_items, itemCount)
            }
            val freeText =
                freeSpaceBytes?.let { "、" + stringResource(R.string.status_free, formatSize(it)) } ?: ""
            Text(
                text = countText + freeText,
                color = colors.textSecondary,
                fontSize = 11.sp,
            )
        }
    }
}
