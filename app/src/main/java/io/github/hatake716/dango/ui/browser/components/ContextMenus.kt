package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.domain.model.EntryKind
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.ui.theme.DangoTheme

/** エントリ右クリックメニューのアクション束（Finder のコンテキストメニュー相当） */
class EntryMenuActions(
    val onOpen: (FsEntry) -> Unit,
    val onPreview: (FsEntry) -> Unit,
    val onBrowseArchive: (FsEntry) -> Unit,
    val onExtractHere: (FsEntry) -> Unit,
    val onExtractOptions: (FsEntry) -> Unit,
    val onExportEntry: (FsEntry) -> Unit,
    val onShare: (FsEntry) -> Unit,
    val onOpenWith: (FsEntry) -> Unit,
    val onCopy: () -> Unit,
    val onCut: () -> Unit,
    val onDuplicate: () -> Unit,
    val onRename: () -> Unit,
    val onCompress: () -> Unit,
    val onDelete: () -> Unit,
    val onRestore: () -> Unit,
    val onInfo: (FsEntry) -> Unit,
    val onToggleTag: (FsEntry, String) -> Unit,
    val dismiss: () -> Unit,
)

@Composable
private fun MenuItem(labelRes: Int, dismiss: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(labelRes)) },
        onClick = {
            dismiss()
            action()
        },
    )
}

/** エントリ上の右クリックメニュー本体 */
@Composable
fun ColumnScope.EntryContextMenuContent(
    entry: FsEntry,
    isTrash: Boolean,
    isArchiveBrowse: Boolean,
    entryTags: Set<String>,
    actions: EntryMenuActions,
) {
    val colors = DangoTheme.colors
    val d = actions.dismiss
    when {
        isTrash -> {
            MenuItem(R.string.act_restore, d) { actions.onRestore() }
            MenuItem(R.string.act_delete_forever, d) { actions.onDelete() }
            HorizontalDivider(color = colors.divider)
            MenuItem(R.string.act_info, d) { actions.onInfo(entry) }
        }
        isArchiveBrowse -> {
            MenuItem(R.string.ctx_open, d) { actions.onOpen(entry) }
            if (!entry.isDir) {
                MenuItem(R.string.ctx_export_entry, d) { actions.onExportEntry(entry) }
            }
        }
        else -> {
            MenuItem(R.string.ctx_open, d) { actions.onOpen(entry) }
            if (!entry.isDir) {
                MenuItem(R.string.act_preview, d) { actions.onPreview(entry) }
            }
            if (entry.kind == EntryKind.ARCHIVE) {
                HorizontalDivider(color = colors.divider)
                MenuItem(R.string.ctx_browse_archive, d) { actions.onBrowseArchive(entry) }
                MenuItem(R.string.ctx_extract_here, d) { actions.onExtractHere(entry) }
                MenuItem(R.string.ctx_extract_options, d) { actions.onExtractOptions(entry) }
            }
            HorizontalDivider(color = colors.divider)
            if (!entry.isDir) {
                MenuItem(R.string.act_share, d) { actions.onShare(entry) }
                MenuItem(R.string.ql_open_with, d) { actions.onOpenWith(entry) }
            }
            MenuItem(R.string.act_copy, d) { actions.onCopy() }
            MenuItem(R.string.act_move, d) { actions.onCut() }
            MenuItem(R.string.act_duplicate, d) { actions.onDuplicate() }
            MenuItem(R.string.act_rename, d) { actions.onRename() }
            MenuItem(R.string.act_compress, d) { actions.onCompress() }
            HorizontalDivider(color = colors.divider)
            MenuItem(R.string.ctx_move_to_trash, d) { actions.onDelete() }
            HorizontalDivider(color = colors.divider)
            // タグ（SPEC §6.3: 7色。タップでトグル）
            if (entry.path.scheme == "file") {
                Text(
                    text = stringResource(R.string.ctx_tags),
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    TAG_COLOR_VALUES.forEach { (tag, color) ->
                        val active = tag in entryTags
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(if (active) color else color.copy(alpha = 0.3f))
                                .then(
                                    if (active) {
                                        Modifier.border(2.dp, colors.textPrimary.copy(alpha = 0.5f), CircleShape)
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { actions.onToggleTag(entry, tag) },
                        )
                    }
                }
                HorizontalDivider(color = colors.divider)
            }
            MenuItem(R.string.act_info, d) { actions.onInfo(entry) }
        }
    }
}

/** 何もない場所の右クリックメニュー */
@Composable
fun ColumnScope.BackgroundContextMenuContent(
    hasClipboard: Boolean,
    isTrash: Boolean,
    isArchiveBrowse: Boolean,
    onNewFolder: () -> Unit,
    onNewTextFile: (String) -> Unit,
    onPaste: () -> Unit,
    onReload: () -> Unit,
    dismiss: () -> Unit,
) {
    if (!isTrash && !isArchiveBrowse) {
        MenuItem(R.string.menu_new_folder, dismiss, onNewFolder)
        MenuItem(R.string.menu_new_text, dismiss) { onNewTextFile("txt") }
        if (hasClipboard) {
            MenuItem(R.string.clip_paste, dismiss, onPaste)
        }
        HorizontalDivider(color = DangoTheme.colors.divider)
    }
    MenuItem(R.string.menu_reload, dismiss, onReload)
}
