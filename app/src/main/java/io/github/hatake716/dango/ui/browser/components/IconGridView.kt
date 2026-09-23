package io.github.hatake716.dango.ui.browser.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.ui.theme.DangoMotion
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
        // ピンチ中は列数が変わるたびの配置アニメを止め、指へ即座に追従させる（SPEC §5）
        var pinching by remember { mutableStateOf(false) }
        val suppressPlacement = LocalSuppressPlacement.current
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = (iconSizeDp + 30).dp),
            modifier = Modifier
                .fillMaxSize()
                // 2本指のときだけズームを拾い、1本指スクロールは素通しする
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        try {
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.size >= 2) {
                                    val zoom = event.calculateZoom()
                                    if (zoom != 1f) {
                                        pinching = true
                                        onPinchZoom(zoom)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            pinching = false
                        }
                    }
                },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
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
                            placementSpec = if (pinching || suppressPlacement) null else DangoMotion.reorder(),
                            fadeOutSpec = DangoMotion.trashFade(),
                        )
                        .marqueeItemBounds(marquee, entry.path.key)
                        .registerItemBounds(entry.path.key),
                )
            }
        }
    MarqueeOverlay(marquee, DangoTheme.colors.textSecondary, Modifier.matchParentSize())
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
            dropBounce.animateTo(1.05f, DangoMotion.bounceUp())
        }
        dropBounce.animateTo(1f, DangoMotion.bounceDown())
    }
    // 選択ハイライトは 80ms でフェード（SPEC §5）。外れるときは素早く
    val selectionSpec = if (selected) DangoMotion.selectionIn<Color>() else DangoMotion.selectionOut()
    val iconBackground by animateColorAsState(
        targetValue = when {
            dropHover -> colors.selectionFocused.copy(alpha = 0.16f)
            selected -> colors.selectionUnfocused
            else -> Color.Transparent
        },
        animationSpec = selectionSpec,
        label = "iconBg",
    )
    val dropBorder by animateColorAsState(
        targetValue = if (dropHover) colors.selectionFocused else Color.Transparent,
        animationSpec = DangoMotion.selectionIn(),
        label = "iconDrop",
    )
    val labelBackground by animateColorAsState(
        targetValue = if (selected && !renaming) colors.selectionFocused else Color.Transparent,
        animationSpec = selectionSpec,
        label = "labelBg",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) colors.onSelection else colors.textPrimary,
        animationSpec = selectionSpec,
        label = "labelText",
    )
    // 貼り付け完了の強調 scale 1.0→1.05→1.0（SPEC §5: 250ms）
    val pulseScale = remember { Animatable(1f) }
    LaunchedEffect(pulse) {
        if (pulse) {
            pulseScale.animateTo(DangoMotion.PULSE_SCALE, tween(DangoMotion.PULSE_HALF_MS))
            pulseScale.animateTo(1f, tween(DangoMotion.PULSE_HALF_MS))
        } else if (pulseScale.value != 1f) {
            // 途中で打ち切られても拡大したまま残さない
            pulseScale.animateTo(1f, DangoMotion.bounceDown())
        }
    }
    val itemAlpha by animateFloatAsState(
        targetValue = when {
            key in hooks.draggingKeys -> 0.5f // ドラッグ元は半透明（SPEC §5）
            entry.isRestricted -> 0.45f
            else -> 1f
        },
        animationSpec = DangoMotion.fade(),
        label = "itemAlpha",
    )
    val iconBox = iconSizeDp.dp
    // 選択時の背板の角丸はアイコンサイズに比例（Finder は約 5pt）
    val backingShape = RoundedCornerShape((iconSizeDp * 0.08f).coerceIn(4f, 10f).dp)
    val dragIcon = rememberVectorPainter(entryIcon(entry.kind))
    val bounds = LocalItemBounds.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pulseScale.value * dropBounce.value
                scaleY = pulseScale.value * dropBounce.value
                // ゴミ箱へ飛んでいる間は元の位置に描かない
                alpha = if (bounds?.hiddenKeys?.contains(key) == true) 0f else itemAlpha
            }
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
                // Finder のアイコンは押下で暗くならない（選択の変化だけで応答する）
                interactionSource = null,
                indication = null,
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
                            dragImage = DragImage(
                                icon = dragIcon,
                                name = entry.name,
                                style = DragImageStyle.GRID,
                                iconSize = iconBox * 0.78f,
                                iconTop = 6.dp + iconBox * 0.11f,
                            ),
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
        FinderMenu(
            expanded = hooks.contextMenuKey == key,
            onDismissRequest = hooks.onContextDismiss,
            offset = hooks.contextMenuOffset,
            atPointer = true,
        ) {
            hooks.contextMenuContent(this, entry)
        }
        Box(
            modifier = Modifier
                .size(iconBox)
                .clip(backingShape)
                .background(iconBackground)
                .border(2.dp, dropBorder, backingShape),
            contentAlignment = Alignment.Center,
        ) {
            EntryThumbnailOrIcon(
                entry = entry,
                thumbSize = iconBox - 10.dp,
                iconSize = iconBox * 0.78f,
                shape = RoundedCornerShape(3.dp),
                fit = true,
            )
        }
        Spacer(Modifier.height(4.dp))
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
            IconLabel(
                name = entry.name,
                tags = tags,
                pillColor = labelBackground,
                textColor = labelColor,
            )
        }
    }
}

private val LabelPillShape = RoundedCornerShape(4.dp)
private val LABEL_PILL_H_PAD = 4.dp

/**
 * アイコン表示の名前ラベル（Finder 同様: 最大2行、行ごとに青い角丸ピル、
 * 収まらない名前は2行目の中央を省略して拡張子を残す）。タグは名前の前に色の点で示す
 */
@Composable
private fun IconLabel(
    name: String,
    tags: Set<String>,
    pillColor: Color,
    textColor: Color,
) {
    val colors = DangoTheme.colors
    val style = LocalTextStyle.current.merge(
        TextStyle(fontSize = 12.sp, lineHeight = 15.sp, textAlign = TextAlign.Center),
    )
    val measurer = rememberTextMeasurer(cacheSize = 4)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val density = LocalDensity.current
        val tagWidth = if (tags.isEmpty()) 0.dp else (8.dp + 4.dp * (tags.size - 1)) + 3.dp
        val maxLinePx = with(density) {
            (maxWidth - LABEL_PILL_H_PAD * 2).roundToPx().coerceAtLeast(1)
        }
        val firstLinePx = with(density) {
            (maxWidth - LABEL_PILL_H_PAD * 2 - tagWidth).roundToPx().coerceAtLeast(1)
        }
        val lines = remember(name, maxLinePx, firstLinePx, style) {
            splitIconLabel(measurer, name, style, firstLinePx)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            lines.forEachIndexed { index, line ->
                Row(
                    modifier = Modifier
                        .clip(LabelPillShape)
                        .background(pillColor)
                        .padding(horizontal = LABEL_PILL_H_PAD, vertical = 0.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (index == 0 && tags.isNotEmpty()) {
                        TagDots(tags, ring = colors.windowBackground)
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        text = line,
                        color = textColor,
                        style = style,
                        maxLines = 1,
                        softWrap = false,
                        overflow = if (index == lines.lastIndex) TextOverflow.MiddleEllipsis else TextOverflow.Clip,
                        modifier = Modifier.widthIn(max = with(density) { maxLinePx.toDp() }),
                    )
                }
            }
        }
    }
}

/** 名前を最大2行に分ける。2行目は描画時に中央省略する（残り全部を渡す） */
private fun splitIconLabel(
    measurer: TextMeasurer,
    name: String,
    style: TextStyle,
    widthPx: Int,
): List<String> {
    val layout = measurer.measure(
        text = name,
        style = style,
        constraints = Constraints(maxWidth = widthPx),
    )
    if (layout.lineCount <= 1) return listOf(name)
    val end = layout.getLineEnd(0, visibleEnd = true).coerceIn(1, name.length)
    val first = name.substring(0, end).trimEnd()
    val rest = name.substring(end).trimStart()
    return if (rest.isEmpty()) listOf(first) else listOf(first, rest)
}

/** 重なり合う小さな色の点（Finder のアイコン表示のタグ） */
@Composable
private fun TagDots(tags: Set<String>, ring: Color) {
    Box {
        tags.forEachIndexed { i, tag ->
            Box(
                modifier = Modifier
                    .padding(start = (i * 4).dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(ring)
                    .padding(1.dp)
                    .clip(CircleShape)
                    .background(TAG_COLOR_VALUES[tag] ?: DangoTheme.colors.textSecondary),
            )
        }
    }
}
