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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
    tagsByKey: Map<String, Set<String>>,
    hooks: EntryItemHooks,
    onMarqueeSelect: (Set<String>) -> Unit,
    onClearSelection: () -> Unit,
    onTap: (FsEntry, Boolean, Boolean) -> Unit,
    onDoubleTap: (FsEntry) -> Unit,
    onLongPress: (FsEntry) -> Unit,
    onPinchZoom: (Float) -> Unit,
    onCommitRename: (String, String) -> Unit,
    onCancelRename: () -> Unit,
) {
    // ラバーバンド選択（SPEC §6.2）: マウスの空白ドラッグで矩形選択
    val marquee = rememberMarqueeState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .marqueeContainer(marquee)
            .marqueeSelectSource(
                marquee,
                enabled = { renamingKey == null },
                currentSelection = { selection },
                onSelect = onMarqueeSelect,
                onClearSelection = onClearSelection,
            ),
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
                    tags = tagsByKey[entry.path.key] ?: emptySet(),
                    hooks = hooks,
                    onTap = onTap,
                    onDoubleTap = onDoubleTap,
                    onLongPress = onLongPress,
                    onCommitRename = onCommitRename,
                    onCancelRename = onCancelRename,
                    modifier = Modifier
                        .animateItem(
                            placementSpec = tween(250),
                            fadeOutSpec = tween(300),
                        )
                        .marqueeItemBounds(marquee, entry.path.key),
                )
            }
        }
    MarqueeOverlay(marquee, DangoTheme.colors.selectionFocused, Modifier.matchParentSize())
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
    tags: Set<String>,
    hooks: EntryItemHooks,
    onTap: (FsEntry, Boolean, Boolean) -> Unit,
    onDoubleTap: (FsEntry) -> Unit,
    onLongPress: (FsEntry) -> Unit,
    onCommitRename: (String, String) -> Unit,
    onCancelRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DangoTheme.colors
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    // クリック時の修飾キー（Ctrl/Shift）は自分の down 時点で記録して onTap に添える
    val clickMods = remember { ClickModifierState() }
    var dropHover by remember { mutableStateOf(false) }
    val key = entry.path.key
    // ドラッグ可否は場所（ゴミ箱・ネットワーク・アーカイブ等）で決まり、一覧内では安定
    val canDrag = !renaming && hooks.dragKeysFor(entry) != null
    // ドロップ先ホバー時にアイコンを軽くバウンス（SPEC §5）
    val dropBounce = remember { Animatable(1f) }
    LaunchedEffect(dropHover) {
        if (dropHover) {
            dropBounce.animateTo(1.05f, tween(90))
            dropBounce.animateTo(1f, tween(140))
        }
    }
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
            .onRightClick { offset ->
                hooks.onContextRequest(
                    entry,
                    with(density) { DpOffset(offset.x.toDp(), offset.y.toDp()) },
                )
            }
            .recordClickModifiers(clickMods)
            .combinedClickable(
                onClick = { onTap(entry, clickMods.ctrl, clickMods.shift) },
                onDoubleClick = { onDoubleTap(entry) },
                // ドラッグ可能な文脈では onLongClick を使わない（consume されてドラッグが
                // 始まらない）。ドラッグ不可の文脈ではドラッグ開始によるタッチキャンセルが
                // 起きず長押し→離すで onClick が発火してしまうため、従来どおり飲み込む
                onLongClick = if (canDrag) {
                    null
                } else {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress(entry)
                    }
                },
            )
            // ドラッグ検出は combinedClickable より内側（後）: Main パスで先にイベントを
            // 受けて未消費の down だけを対象にする（右クリック等の down を除外）。
            // 長押し（選択）→そのまま指を動かすとドラッグ開始、という一続きの操作になる
            .then(
                if (canDrag) {
                    Modifier
                        .longPressObserver {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress(entry)
                        }
                        .entryDragSource(
                            // マウスは長押しを経ないため、ここで選択を整えて
                            // 表示とペイロードを一致させる（Finder 同様の単独選択切替）
                            onMouseDragStart = { hooks.selectForDrag(entry) },
                        ) {
                            hooks.dragKeysFor(entry)?.also { hooks.onDragStart(it) }
                        }
                } else {
                    Modifier
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
                    contentDescription = entry.name,
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
        if (tags.isNotEmpty()) {
            Row(modifier = Modifier.padding(top = 2.dp)) {
                tags.forEach { tag ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 1.dp)
                            .size(7.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(TAG_COLOR_VALUES[tag] ?: colors.textSecondary),
                    )
                }
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
