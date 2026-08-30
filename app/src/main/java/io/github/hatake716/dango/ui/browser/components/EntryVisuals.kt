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

/** タグ7色（SPEC §9 のトークン） */
val TAG_COLOR_VALUES: Map<String, Color> = mapOf(
    "red" to Color(0xFFFF5257),
    "orange" to Color(0xFFFFA234),
    "yellow" to Color(0xFFFFD848),
    "green" to Color(0xFF62C554),
    "blue" to Color(0xFF2E8EFF),
    "purple" to Color(0xFFC67BFF),
    "gray" to Color(0xFFA0A0A5),
)

fun tagLabelRes(tag: String): Int = when (tag) {
    "red" -> io.github.hatake716.dango.R.string.tag_red
    "orange" -> io.github.hatake716.dango.R.string.tag_orange
    "yellow" -> io.github.hatake716.dango.R.string.tag_yellow
    "green" -> io.github.hatake716.dango.R.string.tag_green
    "blue" -> io.github.hatake716.dango.R.string.tag_blue
    "purple" -> io.github.hatake716.dango.R.string.tag_purple
    else -> io.github.hatake716.dango.R.string.tag_gray
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
