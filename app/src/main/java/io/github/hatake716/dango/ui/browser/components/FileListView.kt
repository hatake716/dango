package io.github.hatake716.dango.ui.browser.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.hatake716.dango.R
import io.github.hatake716.dango.domain.model.SortKey
import io.github.hatake716.dango.domain.model.SortSpec
import io.github.hatake716.dango.ui.browser.TreeRow
import io.github.hatake716.dango.ui.theme.DangoTheme
import io.github.hatake716.dango.ui.util.formatDateTime
import io.github.hatake716.dango.ui.util.formatSize
import io.github.hatake716.dango.ui.util.kindLabel

private val DATE_WIDTH = 128.dp
private val SIZE_WIDTH = 76.dp
private val KIND_WIDTH = 112.dp

/** リスト表示（SPEC §4.4: ▸ でツリー展開。列カスタマイズは M5） */
@Composable
fun FileListView(
    rows: List<TreeRow>,
    selection: Set<String>,
    sort: SortSpec,
    renamingKey: String?,
    pastedKeys: Set<String>,
    tagsByKey: Map<String, Set<String>>,
    hooks: EntryItemHooks,
    onTap: (io.github.hatake716.dango.domain.model.FsEntry) -> Unit,
    onDoubleTap: (io.github.hatake716.dango.domain.model.FsEntry) -> Unit,
    onLongPress: (io.github.hatake716.dango.domain.model.FsEntry) -> Unit,
    onToggleExpand: (io.github.hatake716.dango.domain.model.FsEntry) -> Unit,
    onSetSortKey: (SortKey) -> Unit,
    onCommitRename: (String, String) -> Unit,
    onCancelRename: () -> Unit,
    showExpanders: Boolean,
) {
    val colors = DangoTheme.colors
    Column(modifier = Modifier.fillMaxSize()) {
        ListHeader(sort, onSetSortKey)
        HorizontalDivider(color = colors.divider)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(rows, key = { _, r -> r.entry.path.key }) { index, row ->
                ListRow(
                    row = row,
                    selected = row.entry.path.key in selection,
                    isAlt = index % 2 == 1,
                    renaming = row.entry.path.key == renamingKey,
                    pulse = row.entry.path.key in pastedKeys,
                    tags = tagsByKey[row.entry.path.key] ?: emptySet(),
                    hooks = hooks,
                    showExpander = showExpanders,
                    onTap = onTap,
                    onDoubleTap = onDoubleTap,
                    onLongPress = onLongPress,
                    onToggleExpand = onToggleExpand,
                    onCommitRename = onCommitRename,
                    onCancelRename = onCancelRename,
                    modifier = Modifier.animateItem(
                        placementSpec = tween(250),
                        fadeInSpec = tween(180),
                        fadeOutSpec = tween(300),
                    ),
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
            .swallowRightClick()
            .clickable { onSetSortKey(key) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (textAlign == TextAlign.End) Arrangement.End else Arrangement.Start,
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
    row: TreeRow,
    selected: Boolean,
    isAlt: Boolean,
    renaming: Boolean,
    pulse: Boolean,
    tags: Set<String>,
    hooks: EntryItemHooks,
    showExpander: Boolean,
    onTap: (io.github.hatake716.dango.domain.model.FsEntry) -> Unit,
    onDoubleTap: (io.github.hatake716.dango.domain.model.FsEntry) -> Unit,
    onLongPress: (io.github.hatake716.dango.domain.model.FsEntry) -> Unit,
    onToggleExpand: (io.github.hatake716.dango.domain.model.FsEntry) -> Unit,
    onCommitRename: (String, String) -> Unit,
    onCancelRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DangoTheme.colors
    val entry = row.entry
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
    // ▸ の 90° 回転（SPEC §5: 180ms）
    val chevronAngle by animateFloatAsState(
        targetValue = if (row.expanded) 90f else 0f,
        animationSpec = tween(180),
        label = "chevron",
    )
    val pulseScale = remember { Animatable(1f) }
    LaunchedEffect(pulse) {
        if (pulse) {
            pulseScale.animateTo(1.05f, tween(125))
            pulseScale.animateTo(1f, tween(125))
        }
    }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    var dropHover by remember { mutableStateOf(false) }
    val key = entry.path.key
    // ドラッグ可否は場所（ゴミ箱・ネットワーク・アーカイブ等）で決まり、一覧内では安定
    val canDrag = !renaming && hooks.dragKeysFor(entry) != null
    // ドロップ先ホバー時に行を軽くバウンス（SPEC §5）
    val dropBounce = remember { Animatable(1f) }
    LaunchedEffect(dropHover) {
        if (dropHover) {
            dropBounce.animateTo(1.03f, tween(90))
            dropBounce.animateTo(1f, tween(140))
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(background)
            .graphicsLayer {
                scaleX = pulseScale.value * dropBounce.value
                scaleY = pulseScale.value * dropBounce.value
            }
            .alpha(
                when {
                    key in hooks.draggingKeys -> 0.5f // ドラッグ元は半透明（SPEC §5）
                    entry.isRestricted -> 0.45f
                    else -> 1f
                },
            )
            .then(
                if (dropHover) {
                    Modifier.border(2.dp, colors.selectionFocused, RoundedCornerShape(4.dp))
                } else {
                    Modifier
                },
            )
            .entryDropTarget(
                enabled = hooks.dropEnabled(entry),
                onHover = { dropHover = it },
                onDropKeys = { keys -> hooks.onDropInto(keys, entry) },
            )
            .onRightClick { offset ->
                hooks.onContextRequest(
                    entry,
                    with(density) { DpOffset(offset.x.toDp(), offset.y.toDp()) },
                )
            }
            .combinedClickable(
                onClick = { onTap(entry) },
                onDoubleClick = { onDoubleTap(entry) },
                // ドラッグ可否での使い分けは IconGridView と同じ理由
                onLongClick = if (canDrag) {
                    null
                } else {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress(entry)
                    }
                },
            )
            // ドラッグ検出は combinedClickable より内側（後）: 未消費の down だけを対象に
            // することで、展開シェブロンや右クリックの down からは始まらない
            .then(
                if (canDrag) {
                    Modifier
                        .longPressObserver {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress(entry)
                        }
                        .entryDragSource {
                            hooks.dragKeysFor(entry)?.also { hooks.onDragStart(it) }
                        }
                } else {
                    Modifier
                },
            )
            .padding(start = (12 + row.depth * 18).dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DropdownMenu(
            expanded = hooks.contextMenuKey == key,
            onDismissRequest = hooks.onContextDismiss,
            offset = hooks.contextMenuOffset,
        ) {
            hooks.contextMenuContent(this, entry)
        }
        if (showExpander) {
            Box(
                modifier = Modifier.size(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (entry.isDir && !entry.isRestricted) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = secondary,
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(chevronAngle)
                            .clip(RoundedCornerShape(3.dp))
                            .clickable { onToggleExpand(entry) },
                    )
                }
            }
            Spacer(Modifier.width(2.dp))
        }
        if (entry.previewUri != null) {
            AsyncImage(
                model = entry.previewUri,
                contentDescription = entry.name,
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
        if (renaming) {
            InlineRenameField(
                initialName = entry.name,
                isDir = entry.isDir,
                textAlign = TextAlign.Start,
                onCommit = { onCommitRename(entry.path.key, it) },
                onCancel = onCancelRename,
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                text = entry.name,
                color = primary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (tags.isNotEmpty()) {
            Row(modifier = Modifier.padding(end = 6.dp)) {
                tags.forEach { tag ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 1.dp)
                            .size(7.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(TAG_COLOR_VALUES[tag] ?: secondary)
                            // 選択行の青背景では青タグが沈むため縁取りで見せる
                            .then(
                                if (selected) {
                                    Modifier.border(
                                        1.dp,
                                        colors.onSelection,
                                        androidx.compose.foundation.shape.CircleShape,
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
        }
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
