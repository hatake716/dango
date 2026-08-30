package io.github.hatake716.dango.ui.browser.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.hatake716.dango.R
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.domain.model.SortKey
import io.github.hatake716.dango.domain.model.SortSpec
import io.github.hatake716.dango.ui.theme.DangoTheme
import io.github.hatake716.dango.ui.util.formatDateTime
import io.github.hatake716.dango.ui.util.formatSize
import io.github.hatake716.dango.ui.util.kindLabel

private val DATE_WIDTH = 128.dp
private val SIZE_WIDTH = 76.dp
private val KIND_WIDTH = 112.dp

/** リスト表示（SPEC §4.4。列のカスタマイズ・▸ツリー展開は M1 以降） */
@Composable
fun FileListView(
    entries: List<FsEntry>,
    selection: Set<String>,
    sort: SortSpec,
    onTap: (FsEntry) -> Unit,
    onDoubleTap: (FsEntry) -> Unit,
    onLongPress: (FsEntry) -> Unit,
    onSetSortKey: (SortKey) -> Unit,
) {
    val colors = DangoTheme.colors
    Column(modifier = Modifier.fillMaxSize()) {
        ListHeader(sort, onSetSortKey)
        HorizontalDivider(color = colors.divider)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(entries, key = { _, e -> e.path.key }) { index, entry ->
                ListRow(
                    entry = entry,
                    selected = entry.path.key in selection,
                    isAlt = index % 2 == 1,
                    onTap = onTap,
                    onDoubleTap = onDoubleTap,
                    onLongPress = onLongPress,
                    modifier = Modifier.animateItem(placementSpec = tween(250)),
                )
            }
        }
    }
}

@Composable
private fun ListHeader(
    sort: SortSpec,
    onSetSortKey: (SortKey) -> Unit,
) {
    val colors = DangoTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(colors.toolbar)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell(stringResource(R.string.col_name), SortKey.NAME, sort, onSetSortKey, Modifier.weight(1f))
        HeaderCell(stringResource(R.string.col_date), SortKey.DATE, sort, onSetSortKey, Modifier.width(DATE_WIDTH))
        HeaderCell(
            stringResource(R.string.col_size), SortKey.SIZE, sort, onSetSortKey,
            Modifier.width(SIZE_WIDTH), TextAlign.End,
        )
        HeaderCell(stringResource(R.string.col_kind), SortKey.KIND, sort, onSetSortKey, Modifier.width(KIND_WIDTH))
    }
}

@Composable
private fun HeaderCell(
    label: String,
    key: SortKey,
    sort: SortSpec,
    onSetSortKey: (SortKey) -> Unit,
    modifier: Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    val colors = DangoTheme.colors
    val active = sort.key == key
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable { onSetSortKey(key) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (textAlign == TextAlign.End) {
            androidx.compose.foundation.layout.Arrangement.End
        } else {
            androidx.compose.foundation.layout.Arrangement.Start
        },
    ) {
        Text(
            text = label,
            color = if (active) colors.textPrimary else colors.textSecondary,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
        if (active) {
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = if (sort.ascending) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(
    entry: FsEntry,
    selected: Boolean,
    isAlt: Boolean,
    onTap: (FsEntry) -> Unit,
    onDoubleTap: (FsEntry) -> Unit,
    onLongPress: (FsEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DangoTheme.colors
    val background by animateColorAsState(
        targetValue = when {
            selected -> colors.selectionFocused
            isAlt -> colors.altRow
            else -> Color.Transparent
        },
        animationSpec = tween(80),
        label = "rowBg",
    )
    val primary = if (selected) colors.onSelection else colors.textPrimary
    val secondary = if (selected) colors.onSelection.copy(alpha = 0.85f) else colors.textSecondary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(background)
            .alpha(if (entry.isRestricted) 0.45f else 1f)
            .combinedClickable(
                onClick = { onTap(entry) },
                onDoubleClick = { onDoubleTap(entry) },
                onLongClick = { onLongPress(entry) },
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.previewUri != null) {
            AsyncImage(
                model = entry.previewUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )
        } else {
            Icon(
                imageVector = entryIcon(entry.kind),
                contentDescription = null,
                tint = if (selected) colors.onSelection else entryTint(entry.kind, colors),
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = entry.name,
            color = primary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatDateTime(entry.lastModified),
            color = secondary,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.width(DATE_WIDTH),
        )
        Text(
            text = if (entry.isDir) "–" else formatSize(entry.size),
            color = secondary,
            fontSize = 12.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(SIZE_WIDTH),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = kindLabel(entry),
            color = secondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(KIND_WIDTH - 8.dp),
        )
    }
}
