package io.github.hatake716.dango.ui.browser.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.ui.theme.DangoTheme

/** アイコン表示（SPEC §4.4。ピンチによるサイズ変更は M1 で対応） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IconGridView(
    entries: List<FsEntry>,
    selection: Set<String>,
    onTap: (FsEntry) -> Unit,
    onDoubleTap: (FsEntry) -> Unit,
    onLongPress: (FsEntry) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 88.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
    ) {
        items(entries, key = { it.path.key }) { entry ->
            IconGridItem(
                entry = entry,
                selected = entry.path.key in selection,
                onTap = onTap,
                onDoubleTap = onDoubleTap,
                onLongPress = onLongPress,
                modifier = Modifier.animateItem(placementSpec = tween(250)),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IconGridItem(
    entry: FsEntry,
    selected: Boolean,
    onTap: (FsEntry) -> Unit,
    onDoubleTap: (FsEntry) -> Unit,
    onLongPress: (FsEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DangoTheme.colors
    // 選択ハイライトは 80ms でフェード（SPEC §5）
    val iconBackground by animateColorAsState(
        targetValue = if (selected) colors.selectionUnfocused else Color.Transparent,
        animationSpec = tween(80),
        label = "iconBg",
    )
    val labelBackground by animateColorAsState(
        targetValue = if (selected) colors.selectionFocused else Color.Transparent,
        animationSpec = tween(80),
        label = "labelBg",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (entry.isRestricted) 0.45f else 1f)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = { onTap(entry) },
                onDoubleClick = { onDoubleTap(entry) },
                onLongClick = { onLongPress(entry) },
            )
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconBackground),
            contentAlignment = Alignment.Center,
        ) {
            if (entry.previewUri != null) {
                AsyncImage(
                    model = entry.previewUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
            } else {
                Icon(
                    imageVector = entryIcon(entry.kind),
                    contentDescription = null,
                    tint = entryTint(entry.kind, colors),
                    modifier = Modifier.size(46.dp),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = entry.name,
            color = if (selected) colors.onSelection else colors.textPrimary,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(labelBackground)
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}
