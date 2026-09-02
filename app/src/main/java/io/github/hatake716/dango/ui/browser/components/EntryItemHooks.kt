package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpOffset
import io.github.hatake716.dango.domain.model.FsEntry

/**
 * グリッド/リスト項目に共通の「右クリックメニュー」「ドラッグ&ドロップ」配線。
 * 引数の爆発を避けるためひとまとめにして受け渡す。
 */
class EntryItemHooks(
    /** ドラッグ中エントリ（半透明表示。SPEC §5） */
    val draggingKeys: Set<String>,
    /** 右クリックメニューを開いている entry key */
    val contextMenuKey: String?,
    val contextMenuOffset: DpOffset,
    val onContextRequest: (FsEntry, DpOffset) -> Unit,
    val onContextDismiss: () -> Unit,
    /** DropdownMenu の中身（エントリごとに構築） */
    val contextMenuContent: @Composable ColumnScope.(FsEntry) -> Unit,
    /** このエントリをドラッグできるか（選択済みのもののみ） */
    val dragKeysFor: (FsEntry) -> Set<String>?,
    val onDragStart: (Set<String>) -> Unit,
    /**
     * マウスのプレス＋ムーブでドラッグが始まる直前の選択整合。
     * Finder 同様「未選択項目のドラッグはその項目の単独選択に切り替える」
     * （選択済み項目なら選択集合ごと運ぶので何もしない）
     */
    val selectForDrag: (FsEntry) -> Unit,
    /** このエントリがドロップ先になれるか（実フォルダのみ） */
    val dropEnabled: (FsEntry) -> Boolean,
    val onDropInto: (Set<String>, FsEntry) -> Unit,
) {
    companion object {
        /** 何も配線しない（ゴミ箱・アーカイブ内など） */
        fun disabled() = EntryItemHooks(
            draggingKeys = emptySet(),
            contextMenuKey = null,
            contextMenuOffset = DpOffset.Zero,
            onContextRequest = { _, _ -> },
            onContextDismiss = {},
            contextMenuContent = {},
            dragKeysFor = { null },
            onDragStart = {},
            selectForDrag = {},
            dropEnabled = { false },
            onDropInto = { _, _ -> },
        )
    }
}
