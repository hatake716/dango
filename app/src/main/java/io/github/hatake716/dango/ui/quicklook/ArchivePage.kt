package io.github.hatake716.dango.ui.quicklook

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.archive.ArchiveEntryMeta
import io.github.hatake716.dango.data.archive.ArchiveIndex
import io.github.hatake716.dango.data.archive.ArchivePasswordException
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.ui.util.formatSize

private const val MAX_ROWS = 5000

/** アーカイブのプレビュー: エントリ一覧ツリー（SPEC §6.5） */
@Composable
fun ArchivePage(
    entry: FsEntry,
    loadIndex: suspend (FsEntry) -> ArchiveIndex,
) {
    val state by produceState<Result<ArchiveIndex>?>(initialValue = null, entry.path.key) {
        value = runCatching { loadIndex(entry) }
    }
    when (val result = state) {
        null -> QlLoading()
        else -> result.fold(
            onSuccess = { index -> ArchiveTree(index) },
            onFailure = { e ->
                QlMessage(
                    if (e is ArchivePasswordException) {
                        stringResource(R.string.archive_password_title)
                    } else {
                        stringResource(R.string.ql_load_error)
                    },
                )
            },
        )
    }
}

@Composable
private fun ArchiveTree(index: ArchiveIndex) {
    // ツリー順に整列（セグメント単位の辞書順比較。文字列連結だと親子が分断されるケースがある）
    val rows = index.entries
        .sortedWith { a, b ->
            val sa = a.segments
            val sb = b.segments
            for (i in 0 until minOf(sa.size, sb.size)) {
                val c = sa[i].compareTo(sb[i], ignoreCase = true)
                if (c != 0) return@sortedWith c
            }
            sa.size - sb.size
        }
        .take(MAX_ROWS)
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = stringResource(R.string.archive_entry_count, index.entries.size) +
                    if (index.entries.size > MAX_ROWS) " (先頭 $MAX_ROWS 件)" else "",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        items(rows, key = { it.segments.joinToString("/") }) { meta ->
            ArchiveRow(meta)
        }
    }
}

@Composable
private fun ArchiveRow(meta: ArchiveEntryMeta) {
    val depth = meta.segments.size - 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (16 + depth * 16).dp, end = 16.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (meta.isDir) Icons.Filled.Folder else Icons.Outlined.InsertDriveFile,
            contentDescription = null,
            tint = if (meta.isDir) Color(0xFF5AA1F2) else Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = meta.segments.last(),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!meta.isDir && meta.size >= 0) {
            Text(
                text = formatSize(meta.size),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
internal fun QlLoading() {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f))
    }
}

@Composable
internal fun QlMessage(text: String) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
    }
}
