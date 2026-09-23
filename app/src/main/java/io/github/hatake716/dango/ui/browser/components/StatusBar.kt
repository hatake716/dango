package io.github.hatake716.dango.ui.browser.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.transfer.OperationKind
import io.github.hatake716.dango.data.transfer.TransferProgress
import io.github.hatake716.dango.ui.theme.DangoMotion
import io.github.hatake716.dango.ui.theme.DangoTheme
import io.github.hatake716.dango.ui.util.formatSize

/** ステータスバー（SPEC §4.5: N項目, 空き容量。転送中は進捗バーにクロスフェードで切り替わる） */
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
            .height(24.dp),
    ) {
        AnimatedContent(
            targetState = transfer,
            // 進捗値の更新ごとではなく、転送の有無が変わったときだけ切り替える
            contentKey = { it != null },
            transitionSpec = { fadeIn(DangoMotion.fade()) togetherWith fadeOut(DangoMotion.fade()) },
            contentAlignment = Alignment.Center,
            label = "statusBar",
            modifier = Modifier.fillMaxSize(),
        ) { current ->
            if (current != null) {
                TransferRow(current, onCancelTransfer)
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferRow(transfer: TransferProgress, onCancel: () -> Unit) {
    val colors = DangoTheme.colors
    // 進捗は飛び飛びに届くので、次の値までなめらかに伸ばす
    val fraction by animateFloatAsState(
        targetValue = transfer.fraction,
        animationSpec = DangoMotion.progress(),
        label = "transferProgress",
    )
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                when (transfer.kind) {
                    OperationKind.COPY -> R.string.transfer_status_copy
                    OperationKind.MOVE -> R.string.transfer_status_move
                    OperationKind.EXTRACT -> R.string.transfer_status_extract
                    OperationKind.COMPRESS -> R.string.transfer_status_compress
                },
            ),
            color = colors.textSecondary,
            fontSize = 11.sp,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .weight(1f)
                .height(4.dp),
            color = colors.selectionFocused,
            // divider だとバーの地（toolbar）とほぼ同じ明るさで溝が見えないため、macOS と同じ文字色 12%
            trackColor = colors.textPrimary.copy(alpha = 0.12f),
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        val cancelLabel = stringResource(R.string.transfer_cancel)
        Box(
            modifier = Modifier
                .padding(start = 2.dp)
                // バーより高い 28dp のタップ領域（はみ出しを許す）
                .requiredSize(28.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = cancelLabel, onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Cancel,
                contentDescription = cancelLabel,
                tint = colors.textSecondary.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
