package io.github.hatake716.dango.ui.browser.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import io.github.hatake716.dango.R
import io.github.hatake716.dango.ui.theme.DangoTheme

/** 選択中に出るボトムアクションバー（SPEC §6.2） */
@Composable
fun BottomActionBar(
    visible: Boolean,
    isTrash: Boolean,
    selectionCount: Int,
    canPreview: Boolean,
    onPreview: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit,
    onRestore: () -> Unit,
) {
    val colors = DangoTheme.colors
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
        exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(220)),
    ) {
        Column {
            HorizontalDivider(color = colors.divider)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.toolbar)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isTrash) {
                    ActionButton(Icons.Outlined.RestoreFromTrash, R.string.act_restore, enabled = selectionCount > 0, onClick = onRestore)
                    ActionButton(Icons.Outlined.DeleteForever, R.string.act_delete_forever, enabled = selectionCount > 0, onClick = onDelete)
                    ActionButton(Icons.Outlined.Info, R.string.act_info, enabled = selectionCount == 1, onClick = onInfo)
                } else {
                    ActionButton(Icons.Outlined.Visibility, R.string.act_preview, enabled = canPreview, onClick = onPreview)
                    ActionButton(Icons.Outlined.Share, R.string.act_share, enabled = selectionCount > 0, onClick = onShare)
                    ActionButton(Icons.Outlined.ContentCopy, R.string.act_copy, enabled = selectionCount > 0, onClick = onCopy)
                    ActionButton(Icons.Outlined.DriveFileMove, R.string.act_move, enabled = selectionCount > 0, onClick = onMove)
                    ActionButton(Icons.Outlined.LibraryAdd, R.string.act_duplicate, enabled = selectionCount > 0, onClick = onDuplicate)
                    ActionButton(Icons.Outlined.DriveFileRenameOutline, R.string.act_rename, enabled = selectionCount > 0, onClick = onRename)
                    // 圧縮は M3 で対応（SPEC §14）
                    ActionButton(Icons.Outlined.FolderZip, R.string.act_compress, enabled = false, onClick = {})
                    ActionButton(Icons.Outlined.Delete, R.string.act_delete, enabled = selectionCount > 0, onClick = onDelete)
                    ActionButton(Icons.Outlined.Info, R.string.act_info, enabled = selectionCount == 1, onClick = onInfo)
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    labelRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = DangoTheme.colors
    val tint = if (enabled) colors.textPrimary else colors.textSecondary.copy(alpha = 0.35f)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .width(64.dp)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(labelRes),
            color = tint,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}
