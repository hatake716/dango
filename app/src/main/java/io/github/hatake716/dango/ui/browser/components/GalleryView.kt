package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.ui.theme.DangoTheme
import io.github.hatake716.dango.ui.util.formatSize
import io.github.hatake716.dango.ui.util.kindLabel

/** ギャラリー表示（SPEC §4.4: 上部に大きなプレビュー、下部にフィルムストリップ） */
@Composable
fun GalleryView(
    entries: List<FsEntry>,
    selection: Set<String>,
    onTap: (FsEntry) -> Unit,
    onDoubleTap: (FsEntry) -> Unit,
    onLongPress: (FsEntry) -> Unit,
) {
    val colors = DangoTheme.colors
    val selected = entries.firstOrNull { it.path.key in selection } ?: entries.firstOrNull()
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                selected == null -> Unit
                selected.previewUri != null -> AsyncImage(
                    model = selected.previewUri,
                    contentDescription = selected.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClick = { onTap(selected) },
                            onDoubleClick = { onDoubleTap(selected) },
                        ),
                )
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = entryIcon(selected.kind),
                        contentDescription = null,
                        tint = entryTint(selected.kind, colors),
                        modifier = Modifier
                            .size(96.dp)
                            .combinedClickable(
                                onClick = { onTap(selected) },
                                onDoubleClick = { onDoubleTap(selected) },
                            ),
                    )
                }
            }
        }
        selected?.let { entry ->
            Text(
                text = entry.name + "  ·  " + kindLabel(entry) +
                    if (!entry.isDir) "  ·  " + formatSize(entry.size) else "",
                color = colors.textSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        // フィルムストリップ
        val stripState = rememberLazyListState()
        LaunchedEffect(selected?.path?.key) {
            val index = entries.indexOfFirst { it.path.key == selected?.path?.key }
            if (index >= 0) stripState.animateScrollToItem(index)
        }
        LazyRow(
            state = stripState,
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .background(colors.toolbar),
        ) {
            items(entries, key = { it.path.key }) { entry ->
                val isSelected = entry.path.key == selected?.path?.key
                Box(
                    modifier = Modifier
                        .padding(6.dp)
                        .size(64.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .then(
                            if (isSelected) {
                                Modifier.border(2.dp, colors.selectionFocused, RoundedCornerShape(6.dp))
                            } else {
                                Modifier
                            },
                        )
                        .combinedClickable(
                            onClick = { onTap(entry) },
                            onDoubleClick = { onDoubleTap(entry) },
                            onLongClick = { onLongPress(entry) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (entry.previewUri != null) {
                        AsyncImage(
                            model = entry.previewUri,
                            contentDescription = entry.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = entryIcon(entry.kind),
                            contentDescription = null,
                            tint = entryTint(entry.kind, colors),
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
        }
    }
}
