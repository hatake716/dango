package io.github.hatake716.dango.ui.browser.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.ui.theme.DangoTheme

/** アイコン表示（SPEC §4.4: ピンチでアイコンサイズ 48〜256dp を連続変更） */
@Composable
fun IconGridView(
    entries: List<FsEntry>,
    selection: Set<String>,
    iconSizeDp: Int,
    renamingKey: String?,
    pastedKeys: Set<String>,
    hooks: EntryItemHooks,
    onTap: (FsEntry) -> Unit,
    onDoubleTap: (FsEntry) -> Unit,
    onLongPress: (FsEntry) -> Unit,
    onPinchZoom: (Float) -> Unit,
    onCommitRename: (String, String) -> Unit,
    onCancelRename: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = (iconSizeDp + 26).dp),
        modifier = Modifier
            .fillMaxSize()
            // 2本指のときだけズームを拾い、1本指スクロールは素通しする
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                onPinchZoom(zoom)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentPadding = PaddingValues(8.dp),
    ) {
        items(entries, key = { it.path.key }) { entry ->
            IconGridItem(
                entry = entry,
                selected = entry.path.key in selection,
                iconSizeDp = iconSizeDp,
                renaming = entry.path.key == renamingKey,
                pulse = entry.path.key in pastedKeys,
                hooks = hooks,
                onTap = onTap,
                onDoubleTap = onDoubleTap,
                onLongPress = onLongPress,
                onCommitRename = onCommitRename,
                onCancelRename = onCancelRename,
                modifier = Modifier.animateItem(
                    placementSpec = tween(250),
                    fadeOutSpec = tween(300),
                ),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IconGridItem(
    entry: FsEntry,
    selected: Boolean,
    iconSizeDp: Int,
    renaming: Boolean,
    pulse: Boolean,
    hooks: EntryItemHooks,
    onTap: (FsEntry) -> Unit,
    onDoubleTap: (FsEntry) -> Unit,
    onLongPress: (FsEntry) -> Unit,
    onCommitRename: (String, String) -> Unit,
    onCancelRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DangoTheme.colors
    val density = LocalDensity.current
    var dropHover by remember { mutableStateOf(false) }
    val key = entry.path.key
    // 選択ハイライトは 80ms でフェード（SPEC §5）
    val iconBackground by animateColorAsState(
        targetValue = if (selected) colors.selectionUnfocused else Color.Transparent,
        animationSpec = tween(80),
        label = "iconBg",
    )
    val labelBackground by animateColorAsState(
        targetValue = if (selected && !renaming) colors.selectionFocused else Color.Transparent,
        animationSpec = tween(80),
        label = "labelBg",
    )
    // 貼り付け完了の強調 scale 1.0→1.05→1.0（SPEC §5: 250ms）
    val pulseScale = remember { Animatable(1f) }
    LaunchedEffect(pulse) {
        if (pulse) {
            pulseScale.animateTo(1.05f, tween(125))
            pulseScale.animateTo(1f, tween(125))
        }
    }
    val iconBox = iconSizeDp.dp
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pulseScale.value
                scaleY = pulseScale.value
            }
            .alpha(
                when {
                    key in hooks.draggingKeys -> 0.5f // ドラッグ元は半透明（SPEC §5）
                    entry.isRestricted -> 0.45f
                    else -> 1f
                },
            )
            .clip(RoundedCornerShape(8.dp))
            // ドロップ先フォルダは青枠でハイライト（SPEC §5）
            .then(
                if (dropHover) {
                    Modifier.border(2.dp, colors.selectionFocused, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                },
            )
            .entryDropTarget(
                enabled = hooks.dropEnabled(entry),
                onHover = { dropHover = it },
                onDropKeys = { keys -> hooks.onDropInto(keys, entry) },
            )
            .then(
                if (selected && !renaming && hooks.dragKeysFor(entry) != null) {
                    Modifier.entryDragSource {
                        hooks.dragKeysFor(entry)?.also { hooks.onDragStart(it) }
                    }
                } else {
                    Modifier
                },
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
                // ドラッグ可能な選択済みアイテムのみ長押しをドラッグに譲る
                // （ゴミ箱等でドラッグ不可なら長押しを選択トグルのまま残す）
                onLongClick = if (selected && hooks.dragKeysFor(entry) != null) {
                    null
                } else {
                    { onLongPress(entry) }
                },
            )
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DropdownMenu(
            expanded = hooks.contextMenuKey == key,
            onDismissRequest = hooks.onContextDismiss,
            offset = hooks.contextMenuOffset,
        ) {
            hooks.contextMenuContent(this, entry)
        }
        Box(
            modifier = Modifier
                .size(iconBox)
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
                        .size(iconBox - 6.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
            } else {
                Icon(
                    imageVector = entryIcon(entry.kind),
                    contentDescription = null,
                    tint = entryTint(entry.kind, colors),
                    modifier = Modifier.size(iconBox * 0.72f),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        if (renaming) {
            InlineRenameField(
                initialName = entry.name,
                isDir = entry.isDir,
                textAlign = TextAlign.Center,
                onCommit = { onCommitRename(entry.path.key, it) },
                onCancel = onCancelRename,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
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
}
