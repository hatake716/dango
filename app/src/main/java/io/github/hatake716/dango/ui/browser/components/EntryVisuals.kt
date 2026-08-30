package io.github.hatake716.dango.ui.browser.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.hatake716.dango.domain.model.EntryKind
import io.github.hatake716.dango.ui.theme.DangoColors

fun entryIcon(kind: EntryKind): ImageVector = when (kind) {
    EntryKind.FOLDER -> Icons.Filled.Folder
    EntryKind.IMAGE -> Icons.Outlined.Image
    EntryKind.VIDEO -> Icons.Outlined.Movie
    EntryKind.AUDIO -> Icons.Outlined.MusicNote
    EntryKind.PDF -> Icons.Outlined.PictureAsPdf
    EntryKind.TEXT -> Icons.Outlined.Description
    EntryKind.ARCHIVE -> Icons.Outlined.Archive
    EntryKind.APK -> Icons.Outlined.Android
    EntryKind.OTHER -> Icons.Outlined.InsertDriveFile
}

fun entryTint(kind: EntryKind, colors: DangoColors): Color = when (kind) {
    EntryKind.FOLDER -> colors.accent
    EntryKind.IMAGE -> Color(0xFF62C554)
    EntryKind.VIDEO -> Color(0xFFC67BFF)
    EntryKind.AUDIO -> Color(0xFFFF5257)
    EntryKind.PDF -> Color(0xFFFF5257) // §9 タグ色（赤）を流用
    EntryKind.TEXT -> colors.textSecondary
    EntryKind.ARCHIVE -> Color(0xFFFFA234)
    EntryKind.APK -> Color(0xFF62C554)
    EntryKind.OTHER -> colors.textSecondary
}
