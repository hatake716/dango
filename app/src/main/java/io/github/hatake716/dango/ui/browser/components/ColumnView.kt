package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import coil3.compose.AsyncImage
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.domain.model.FsPath
import io.github.hatake716.dango.ui.theme.DangoTheme
import io.github.hatake716.dango.ui.util.formatDateTime
import io.github.hatake716.dango.ui.util.formatSize
import io.github.hatake716.dango.ui.util.kindLabel

/**
 * カラム表示（SPEC §4.4: 横スクロールの複数ペイン。
 * 幅600dp未満は2列固定＋横スワイプ、末尾に選択項目のプレビュー列）。
 */
@Composable
fun ColumnView(
    basePath: FsPath,
    currentPath: FsPath,
    selection: Set<String>,
    loadChildren: suspend (FsPath) -> List<FsEntry>,
    onNavigate: (FsPath) -> Unit,
    onTapFile: (FsEntry) -> Unit,
    onDoubleTapFile: (FsEntry) -> Unit,
) {
    val colors = DangoTheme.colors
    // base から currentPath までの祖先チェーンが各列になる
    val chain: List<FsPath> = remember(basePath, currentPath) {
        if (currentPath.scheme == basePath.scheme &&
            currentPath.isDescendantOf(basePath)
        ) {
            val paths = mutableListOf(basePath)
            var p = basePath
            for (segment in currentPath.segments.drop(basePath.segments.size)) {
                p = p.child(segment)
                paths += p
            }
            paths
        } else {
            listOf(currentPath)
        }
    }
    // 選択中の単一ファイル（プレビュー列。SPEC §4.4）
    var previewEntry by remember { mutableStateOf<FsEntry?>(null) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columnWidth = if (maxWidth < 600.dp) maxWidth / 2 else 240.dp
        val listState = rememberLazyListState()
        // カラム追加時は右端へ自動スクロール（SPEC §5: 200ms スライドイン）
        LaunchedEffect(chain.size, previewEntry != null) {
            listState.animateScrollToItem((chain.size + if (previewEntry != null) 1 else 0) - 1)
        }
        LazyRow(state = listState, modifier = Modifier.fillMaxSize()) {
            items(chain.size) { index ->
                val path = chain[index]
                Row {
                    ColumnPane(
                        path = path,
                        width = columnWidth,
                        selectedChild = chain.getOrNull(index + 1)?.name,
                        selection = selection,
                        loadChildren = loadChildren,
                        onEntryTap = { entry ->
                            if (entry.isDir) {
                                previewEntry = null
                                onNavigate(entry.path)
                            } else {
                                previewEntry = entry
                                onTapFile(entry)
                            }
                        },
                        onEntryDoubleTap = { entry ->
                            if (!entry.isDir) onDoubleTapFile(entry)
                        },
                    )
                    VerticalDivider(color = colors.divider, modifier = Modifier.fillMaxHeight())
                }
            }
            previewEntry?.let { entry ->
                item(key = "preview") {
                    PreviewPane(entry, columnWidth)
                }
            }
        }
    }
}

@Composable
private fun ColumnPane(
    path: FsPath,
    width: androidx.compose.ui.unit.Dp,
    selectedChild: String?,
    selection: Set<String>,
    loadChildren: suspend (FsPath) -> List<FsEntry>,
    onEntryTap: (FsEntry) -> Unit,
    onEntryDoubleTap: (FsEntry) -> Unit,
) {
    val colors = DangoTheme.colors
    val entries by produceState<List<FsEntry>?>(initialValue = null, path.key) {
        value = loadChildren(path)
    }
    Box(modifier = Modifier.width(width).fillMaxHeight()) {
        when {
            entries == null -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(22.dp),
                color = colors.accent,
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries.orEmpty(), key = { it.path.key }) { entry ->
                    val isPathSelected = entry.isDir && entry.name == selectedChild
                    val isFileSelected = entry.path.key in selection
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .background(
                                when {
                                    isPathSelected -> colors.selectionFocused
                                    isFileSelected -> colors.selectionUnfocused
                                    else -> colors.windowBackground
                                },
                            )
                            .combinedClickable(
                                onClick = { onEntryTap(entry) },
                                onDoubleClick = { onEntryDoubleTap(entry) },
                            )
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = entryIcon(entry.kind),
                            contentDescription = null,
                            tint = if (isPathSelected) colors.onSelection else entryTint(entry.kind, colors),
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = entry.name,
                            color = if (isPathSelected) colors.onSelection else colors.textPrimary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (entry.isDir) {
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = if (isPathSelected) colors.onSelection else colors.textSecondary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewPane(entry: FsEntry, width: androidx.compose.ui.unit.Dp) {
    val colors = DangoTheme.colors
    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(colors.windowBackground)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        if (entry.previewUri != null) {
            AsyncImage(
                model = entry.previewUri,
                contentDescription = entry.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(width - 48.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Icon(
                imageVector = entryIcon(entry.kind),
                contentDescription = null,
                tint = entryTint(entry.kind, colors),
                modifier = Modifier.size(72.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = entry.name,
            color = colors.textPrimary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = kindLabel(entry) + "\n" + formatSize(entry.size) + "\n" +
                formatDateTime(entry.lastModified),
            color = colors.textSecondary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
    }
}
