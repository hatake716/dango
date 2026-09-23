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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.domain.model.SortKey
import io.github.hatake716.dango.domain.model.SortSpec
import io.github.hatake716.dango.ui.browser.TreeRow
import io.github.hatake716.dango.ui.theme.DangoMotion
import io.github.hatake716.dango.ui.theme.DangoTheme
import io.github.hatake716.dango.ui.util.formatDateTime
import io.github.hatake716.dango.ui.util.formatSize
import io.github.hatake716.dango.ui.util.kindLabel
import kotlin.math.roundToInt

// 列幅の下限（ヘッダ境界のドラッグで再配分する。名前列は残り幅）
private val MIN_NAME_WIDTH = 64.dp
private val MIN_DATE_WIDTH = 56.dp
private val MIN_SIZE_WIDTH = 40.dp
private val MIN_KIND_WIDTH = 48.dp

/** 列幅の上限。SettingsRepository の永続化クランプと必ず一致させること */
private val MAX_COL_WIDTH = 400.dp

/** 列境界ドラッグハンドルのタッチ幅 */
private val HANDLE_WIDTH = 18.dp

/** リスト列の実効幅（クランプ適用後） */
private data class ListColumnWidths(val date: Dp, val size: Dp, val kind: Dp)

/**
 * 列幅をレイアウト（測定）フェーズで State から読むモディファイア。
 * Modifier.width(dp) の差し替えでは「再コンポーズ→Modifier差分→再測定」を経由するが、
 * この経路は環境によって再測定が適用されないことがある（実機 Android 17 で、
 * ドラッグ中に行が再コンポーズされてもレイアウトが動かない現象を確認）。
 * 測定ラムダ内で snapshot state を読めば、幅の変更はレイアウト無効化として
 * 直接伝わり、再コンポーズにもModifier差分にも依存しない。
 */
private fun Modifier.columnWidth(width: () -> Dp): Modifier =
    layout { measurable, constraints ->
        val w = width().roundToPx().coerceIn(0, constraints.maxWidth)
        val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }


/** リスト表示（SPEC §4.4: ▸ でツリー展開、列幅はヘッダ境界のドラッグで調整） */
@Composable
fun FileListView(
    rows: List<TreeRow>,
    selection: Set<String>,
    sort: SortSpec,
    renamingKey: String?,
    pastedKeys: Set<String>,
    tagsByKey: Map<String, Set<String>>,
    hooks: EntryItemHooks,
    dateWidthDp: Int,
    sizeWidthDp: Int,
    kindWidthDp: Int,
    onSetColumnWidths: (Int, Int, Int) -> Unit,
    onMarqueeSelect: (Set<String>) -> Unit,
    onClearSelection: () -> Unit,
    onTap: (io.github.hatake716.dango.domain.model.FsEntry, Boolean, Boolean) -> Unit,
    onDoubleTap: (io.github.hatake716.dango.domain.model.FsEntry) -> Unit,
    onLongPress: (io.github.hatake716.dango.domain.model.FsEntry) -> Unit,
    onToggleExpand: (io.github.hatake716.dango.domain.model.FsEntry) -> Unit,
    onSetSortKey: (SortKey) -> Unit,
    onCommitRename: (String, String) -> Unit,
    onCancelRename: () -> Unit,
    showExpanders: Boolean,
) {
    val colors = DangoTheme.colors
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 保存された列幅が現在のペイン幅に収まらない場合（横画面で広げて縦に戻した等）は
        // 描画用に日付→サイズ→種類の順で最小値まで縮めて収める。ハンドル位置・ドラッグの
        // 基準も同じ実効値を使うので、次のドラッグで収まる値が保存され自己修復する
        var dateWidth = dateWidthDp.dp
        var sizeWidth = sizeWidthDp.dp
        var kindWidth = kindWidthDp.dp
        val available = maxWidth - 16.dp - MIN_NAME_WIDTH
        val over = (dateWidth + sizeWidth + kindWidth) - available
        if (over > 0.dp) {
            val dCut = minOf(over, dateWidth - MIN_DATE_WIDTH).coerceAtLeast(0.dp)
            dateWidth -= dCut
            val sCut = minOf(over - dCut, sizeWidth - MIN_SIZE_WIDTH).coerceAtLeast(0.dp)
            sizeWidth -= sCut
            val kCut = minOf(over - dCut - sCut, kindWidth - MIN_KIND_WIDTH).coerceAtLeast(0.dp)
            kindWidth -= kCut
        }
        // 行へは snapshot state 経由で列幅を渡す。LazyColumn のアイテムは独立した
        // 再コンポーズスコープで、キャプチャ値の変化（コンテンツラムダの再生成）による
        // 伝播は環境によって働かず「ヘッダだけ動いて行が止まる」ことがあるため、
        // 各行に State を読ませて確実にドラッグへ追従させる
        val widthsState = rememberUpdatedState(ListColumnWidths(dateWidth, sizeWidth, kindWidth))
        // 行集合の変化が「フォルダの展開/折りたたみ」か（並べ替えではないか）を判定し、
        // 展開で新しく現れた行だけ上から出現させる（SPEC §5: 180ms）
        val rowKeys = remember(rows) { rows.map { it.entry.path.key } }
        val previousKeys = remember { arrayOf<List<String>>(emptyList()) }
        val change = remember(rowKeys) { classifyRowChange(previousKeys[0], rowKeys) }
        SideEffect { previousKeys[0] = rowKeys }
        // 出現アニメは各行につき一度だけ（スクロールで画面外から戻った行では再生しない）
        val pendingReveal = remember(change) { change.inserted.toHashSet() }
        val placementSpec = when {
            LocalSuppressPlacement.current -> null
            change.isExpandOrCollapse -> DangoMotion.expand<IntOffset>()
            else -> DangoMotion.reorder()
        }
        val listState = rememberLazyListState()
        Column(modifier = Modifier.fillMaxSize()) {
            ListHeader(sort, onSetSortKey, widthsState, onSetColumnWidths)
            HorizontalDivider(thickness = 0.5.dp, color = colors.divider)
            // ラバーバンド選択（SPEC §6.2）: マウスの空白ドラッグで矩形選択
            val marquee = rememberMarqueeState()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 縞はテーブル側に固定して描く（Finder 同様）。行に持たせると並べ替え・
                    // 展開で動く行と一緒に縞も流れてちらつくため。空き領域の下端まで続ける
                    .drawBehind { drawListStripes(listState, colors.altRow) }
                    .marqueeContainer(marquee)
                    .marqueeSelectSource(
                        marquee,
                        enabled = { renamingKey == null },
                        currentSelection = { selection },
                        onSelect = onMarqueeSelect,
                        onClearSelection = onClearSelection,
                    ),
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(rows, key = { _, r -> r.entry.path.key }) { index, row ->
                        val key = row.entry.path.key
                        val selected = key in selection
                        ListRow(
                            row = row,
                            selected = selected,
                            // 連続する選択行は1つの角丸ブロックにつなげる（Finder の inset 表示）
                            joinAbove = selected && index > 0 &&
                                rows[index - 1].entry.path.key in selection,
                            joinBelow = selected && index < rows.lastIndex &&
                                rows[index + 1].entry.path.key in selection,
                            revealOnAppear = pendingReveal.remove(key),
                            renaming = key == renamingKey,
                            pulse = key in pastedKeys,
                            tags = tagsByKey[key] ?: emptySet(),
                            hooks = hooks,
                            widths = widthsState,
                            showExpander = showExpanders,
                            onTap = onTap,
                            onDoubleTap = onDoubleTap,
                            onLongPress = onLongPress,
                            onToggleExpand = onToggleExpand,
                            onCommitRename = onCommitRename,
                            onCancelRename = onCancelRename,
                            modifier = Modifier
                                .animateItem(
                                    placementSpec = placementSpec,
                                    fadeInSpec = DangoMotion.expand(),
                                    // 折りたたみでは子を先に消し、その上を下の行が詰める
                                    fadeOutSpec = if (change.isExpandOrCollapse) {
                                        DangoMotion.collapseOut()
                                    } else {
                                        DangoMotion.trashFade()
                                    },
                                )
                                .marqueeItemBounds(marquee, key)
                                .registerItemBounds(key),
                        )
                    }
                }
                MarqueeOverlay(marquee, colors.textSecondary, Modifier.matchParentSize())
            }
        }
    }
}

private val ROW_HEIGHT = 32.dp

/** 行の選択背景・縞の左右インセットと角丸（Big Sur 以降の inset テーブル） */
private val ROW_INSET = 6.dp
private val ROW_RADIUS = 6.dp

/** 行集合の差分。展開/折りたたみ（相対順序を保った挿入・削除のみ）かと、挿入された行 */
private class RowChange(val isExpandOrCollapse: Boolean, val inserted: Set<String>)

private fun classifyRowChange(before: List<String>, after: List<String>): RowChange {
    if (before.isEmpty() || before == after) return RowChange(false, emptySet())
    val beforeSet = before.toHashSet()
    val afterSet = after.toHashSet()
    val keptBefore = before.filter { it in afterSet }
    val keptAfter = after.filter { it in beforeSet }
    // 共通部分の並びが変わっていれば並べ替え（または別フォルダへの遷移）
    if (keptBefore != keptAfter || keptAfter.isEmpty()) return RowChange(false, emptySet())
    val inserted = after.filterTo(HashSet()) { it !in beforeSet }
    val removed = before.any { it !in afterSet }
    // 挿入と削除が同時に起きるのは再読み込み等なので、展開扱いにしない
    return if (inserted.isNotEmpty() && removed) {
        RowChange(false, emptySet())
    } else {
        RowChange(true, inserted)
    }
}

/** 表示中の行位置に合わせて縞（交互行）を描く。行が無い下端の空きにも続ける */
private fun DrawScope.drawListStripes(state: LazyListState, color: Color) {
    val rowPx = ROW_HEIGHT.roundToPx().toFloat()
    if (rowPx <= 0f) return
    val inset = ROW_INSET.toPx()
    val radius = CornerRadius(ROW_RADIUS.toPx())
    var index = state.firstVisibleItemIndex
    var y = -state.firstVisibleItemScrollOffset.toFloat()
    while (y < size.height) {
        if (index % 2 == 1) {
            drawRoundRect(
                color = color,
                topLeft = Offset(inset, y),
                size = Size(size.width - inset * 2, rowPx),
                cornerRadius = radius,
            )
        }
        y += rowPx
        index++
    }
}

@Composable
private fun ListHeader(
    sort: SortSpec,
    onSetSortKey: (SortKey) -> Unit,
    widths: State<ListColumnWidths>,
    onSetColumnWidths: (Int, Int, Int) -> Unit,
) {
    val colors = DangoTheme.colors
    val density = LocalDensity.current
    var headerWidthPx by remember { mutableIntStateOf(0) }
    // 各ハンドルにドラッグ開始時点の列幅（date/size/kind の dp）を渡す。
    // 移動量は開始時からの累積で適用する（フレームごとの差分を Int に丸めると
    // 1px 未満の動きが失われて追従しないため）
    val currentWidths = {
        val w = widths.value
        floatArrayOf(w.date.value, w.size.value, w.kind.value)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(colors.windowBackground)
            .onSizeChanged { headerWidthPx = it.width },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderCell(stringResource(R.string.col_name), SortKey.NAME, sort, onSetSortKey, Modifier.weight(1f))
            HeaderCell(
                stringResource(R.string.col_date), SortKey.DATE, sort, onSetSortKey,
                Modifier.columnWidth { widths.value.date },
            )
            HeaderCell(
                stringResource(R.string.col_size), SortKey.SIZE, sort, onSetSortKey,
                Modifier.columnWidth { widths.value.size }, TextAlign.End,
            )
            HeaderCell(
                stringResource(R.string.col_kind), SortKey.KIND, sort, onSetSortKey,
                Modifier.columnWidth { widths.value.kind },
            )
        }
        // 名前|変更日: 変更日の幅だけを変える（名前列が残りを吸収する）
        ColumnResizeHandle(
            boundaryFromRight = { with(widths.value) { 8.dp + kind + size + date } },
            currentWidths = currentWidths,
            onResize = { start, moved ->
                // 名前列の最小幅を割らない範囲で変更日を広げられる。
                // 上限は永続化側の MAX_COL_WIDTH とも揃える（超過分の書き戻しを防ぐ）
                val totalDp = with(density) { headerWidthPx.toDp().value }
                val maxDate = minOf(
                    MAX_COL_WIDTH.value,
                    totalDp - 16f - MIN_NAME_WIDTH.value - start[1] - start[2],
                )
                val newDate = (start[0] - moved)
                    .coerceIn(MIN_DATE_WIDTH.value, maxOf(MIN_DATE_WIDTH.value, maxDate))
                onSetColumnWidths(
                    newDate.roundToInt(),
                    start[1].roundToInt(),
                    start[2].roundToInt(),
                )
            },
        )
        // 変更日|サイズ: 隣接2列で幅を再配分（境界が指に追従する）
        ColumnResizeHandle(
            boundaryFromRight = { with(widths.value) { 8.dp + kind + size } },
            currentWidths = currentWidths,
            onResize = { start, moved ->
                val m = moved.coerceIn(
                    maxOf(MIN_DATE_WIDTH.value - start[0], start[1] - MAX_COL_WIDTH.value),
                    minOf(start[1] - MIN_SIZE_WIDTH.value, MAX_COL_WIDTH.value - start[0]),
                )
                onSetColumnWidths(
                    (start[0] + m).roundToInt(),
                    (start[1] - m).roundToInt(),
                    start[2].roundToInt(),
                )
            },
        )
        // サイズ|種類
        ColumnResizeHandle(
            boundaryFromRight = { 8.dp + widths.value.kind },
            currentWidths = currentWidths,
            onResize = { start, moved ->
                val m = moved.coerceIn(
                    maxOf(MIN_SIZE_WIDTH.value - start[1], start[2] - MAX_COL_WIDTH.value),
                    minOf(start[2] - MIN_KIND_WIDTH.value, MAX_COL_WIDTH.value - start[1]),
                )
                onSetColumnWidths(
                    start[0].roundToInt(),
                    (start[1] + m).roundToInt(),
                    (start[2] - m).roundToInt(),
                )
            },
        )
    }
}

/**
 * 列境界のドラッグハンドル。境界に細い縦線を描き、左右ドラッグで列幅を調整する。
 * onResize にはドラッグ開始時点の列幅スナップショットと、開始からの累積移動量
 * （dp、右方向が正）を渡す。スナップショットはハンドルごとに独立して持つ
 * （共有すると2本指で別ハンドルを同時に掴んだとき互いの基準値を壊す）
 */
@Composable
private fun BoxScope.ColumnResizeHandle(
    boundaryFromRight: () -> Dp,
    currentWidths: () -> FloatArray,
    onResize: (FloatArray, Float) -> Unit,
) {
    val colors = DangoTheme.colors
    val density = LocalDensity.current
    val start = remember { floatArrayOf(0f, 0f, 0f) }
    var movedDp by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            // 配置フェーズで State を読む（offset ラムダは placement ごとに再評価される）
            .offset { IntOffset(-(boundaryFromRight() - HANDLE_WIDTH / 2).roundToPx(), 0) }
            .width(HANDLE_WIDTH)
            .fillMaxHeight()
            // ヘッダ上の右クリックはセル同様ここでも握りつぶす
            // （背景コンテキストメニューがヘッダ上で開かないように）
            .swallowRightClick()
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { deltaPx ->
                    movedDp += with(density) { deltaPx.toDp().value }
                    onResize(start, movedDp)
                },
                onDragStarted = {
                    movedDp = 0f
                    currentWidths().copyInto(start)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(14.dp)
                .background(colors.divider),
        )
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
    // Finder の列ヘッダ: 11pt の補助色。並べ替え中の列は本文色になり、
    // セルの末尾に小さなシェブロン（∧/∨）が付く
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
            fontSize = 11.5.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (textAlign == TextAlign.End) Modifier else Modifier.weight(1f, fill = false),
        )
        if (active) {
            if (textAlign != TextAlign.End) Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(3.dp))
            Icon(
                imageVector = if (sort.ascending) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(
    row: TreeRow,
    selected: Boolean,
    joinAbove: Boolean,
    joinBelow: Boolean,
    revealOnAppear: Boolean,
    renaming: Boolean,
    pulse: Boolean,
    tags: Set<String>,
    hooks: EntryItemHooks,
    widths: State<ListColumnWidths>,
    showExpander: Boolean,
    onTap: (io.github.hatake716.dango.domain.model.FsEntry, Boolean, Boolean) -> Unit,
    onDoubleTap: (io.github.hatake716.dango.domain.model.FsEntry) -> Unit,
    onLongPress: (io.github.hatake716.dango.domain.model.FsEntry) -> Unit,
    onToggleExpand: (io.github.hatake716.dango.domain.model.FsEntry) -> Unit,
    onCommitRename: (String, String) -> Unit,
    onCancelRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DangoTheme.colors
    val entry = row.entry
    // 列幅はコンポーズでは読まない: 各セルの columnWidth（測定フェーズ）で State を
    // 読むため、ドラッグ中の幅変化は行の再コンポーズなしにレイアウトへ直接伝わる
    // クリック時の修飾キー（Ctrl/Shift）は自分の down 時点で記録して onTap に添える
    val clickMods = remember { ClickModifierState() }
    var dropHover by remember { mutableStateOf(false) }
    val selectionSpec = if (selected) DangoMotion.selectionIn<Color>() else DangoMotion.selectionOut()
    val background by animateColorAsState(
        targetValue = when {
            selected -> colors.selectionFocused
            dropHover -> colors.selectionFocused.copy(alpha = 0.14f)
            else -> Color.Transparent
        },
        animationSpec = selectionSpec,
        label = "rowBg",
    )
    val primary by animateColorAsState(
        targetValue = if (selected) colors.onSelection else colors.textPrimary,
        animationSpec = selectionSpec,
        label = "rowText",
    )
    val secondary by animateColorAsState(
        targetValue = if (selected) colors.onSelection.copy(alpha = 0.85f) else colors.textSecondary,
        animationSpec = selectionSpec,
        label = "rowText2",
    )
    val dropBorder by animateColorAsState(
        targetValue = if (dropHover) colors.selectionFocused else Color.Transparent,
        animationSpec = DangoMotion.selectionIn(),
        label = "rowDrop",
    )
    val shape = RoundedCornerShape(
        topStart = if (joinAbove) 0.dp else ROW_RADIUS,
        topEnd = if (joinAbove) 0.dp else ROW_RADIUS,
        bottomStart = if (joinBelow) 0.dp else ROW_RADIUS,
        bottomEnd = if (joinBelow) 0.dp else ROW_RADIUS,
    )
    // ▸ の 90° 回転（SPEC §5: 180ms）
    val chevronAngle by animateFloatAsState(
        targetValue = if (row.expanded) 90f else 0f,
        animationSpec = DangoMotion.expand(),
        label = "chevron",
    )
    // 展開で現れた子行は少し上から降りてくる（高さが伸びて見えるように）
    val reveal = remember { Animatable(if (revealOnAppear) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (reveal.value < 1f) reveal.animateTo(1f, DangoMotion.expand())
    }
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
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val key = entry.path.key
    // ドラッグ可否は場所（ゴミ箱・ネットワーク・アーカイブ等）で決まり、一覧内では安定
    val canDrag = !renaming && hooks.dragKeysFor(entry) != null
    // ドロップ先ホバー時に行を軽くバウンス（SPEC §5）
    val dropBounce = remember { Animatable(1f) }
    LaunchedEffect(dropHover) {
        if (dropHover) {
            dropBounce.animateTo(1.03f, DangoMotion.bounceUp())
        }
        dropBounce.animateTo(1f, DangoMotion.bounceDown())
    }
    val dragAlpha by animateFloatAsState(
        targetValue = when {
            key in hooks.draggingKeys -> 0.5f // ドラッグ元は半透明（SPEC §5）
            entry.isRestricted -> 0.45f
            else -> 1f
        },
        animationSpec = DangoMotion.fade(),
        label = "dragAlpha",
    )
    val dragIcon = rememberVectorPainter(entryIcon(entry.kind))
    val bounds = LocalItemBounds.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .drawBehind {
                // 選択・ドロップ先の背景は左右をインセットした角丸（内容の位置は動かさない）
                val inset = ROW_INSET.toPx()
                val bodySize = Size(size.width - inset * 2, size.height)
                val outline = shape.createOutline(bodySize, layoutDirection, this)
                translate(left = inset) {
                    if (background.alpha > 0f) drawOutline(outline, background)
                    if (dropBorder.alpha > 0f) {
                        drawOutline(outline, dropBorder, style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }
            .graphicsLayer {
                val scale = pulseScale.value * dropBounce.value
                scaleX = scale
                scaleY = scale
                // 行全体を中央基準で拡大すると文字が横に流れるため、左端を基準にする
                transformOrigin = TransformOrigin(0f, 0.5f)
                alpha = if (bounds?.hiddenKeys?.contains(key) == true) {
                    0f // ゴミ箱へ飛んでいる間は元の位置に描かない
                } else {
                    dragAlpha * (0.4f + 0.6f * reveal.value)
                }
                translationY = -(1f - reveal.value) * size.height * 0.5f
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
                // Finder の行は押下で暗くならない（選択の変化だけで応答する）
                interactionSource = null,
                indication = null,
                onClick = { onTap(entry, clickMods.ctrl, clickMods.shift) },
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
                        .entryDragSource(
                            dragImage = DragImage(
                                icon = dragIcon,
                                name = entry.name,
                                style = DragImageStyle.ROW,
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
            .padding(start = (12 + row.depth * 18).dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FinderMenu(
            expanded = hooks.contextMenuKey == key,
            onDismissRequest = hooks.onContextDismiss,
            offset = hooks.contextMenuOffset,
            atPointer = true,
        ) {
            hooks.contextMenuContent(this, entry)
        }
        if (showExpander) {
            val canExpand = entry.isDir && !entry.isRestricted
            Box(
                modifier = Modifier
                    .size(width = 18.dp, height = ROW_HEIGHT)
                    .then(
                        if (canExpand) {
                            Modifier.clickable(
                                interactionSource = null,
                                indication = null,
                            ) { onToggleExpand(entry) }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (canExpand) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = secondary,
                        modifier = Modifier
                            .size(15.dp)
                            .rotate(chevronAngle),
                    )
                }
            }
            Spacer(Modifier.width(2.dp))
        }
        // 種類アイコンは選択中も Finder 同様に固有色のまま（白 tint で潰さない）
        EntryThumbnailOrIcon(
            entry = entry,
            thumbSize = 18.dp,
            iconSize = 18.dp,
            shape = RoundedCornerShape(2.dp),
            fit = true,
        )
        Spacer(Modifier.width(8.dp))
        if (renaming) {
            InlineRenameField(
                initialName = entry.name,
                isDir = entry.isDir,
                textAlign = TextAlign.Start,
                fontSize = 13.sp,
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
                // Finder 同様、長い名前は中央を省略して拡張子を残す
                overflow = TextOverflow.MiddleEllipsis,
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
            modifier = Modifier.columnWidth { widths.value.date },
        )
        Text(
            text = if (entry.isDir) "--" else formatSize(entry.size),
            color = secondary,
            fontSize = 12.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.columnWidth { widths.value.size },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = kindLabel(entry),
            color = secondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.columnWidth { widths.value.kind - 8.dp },
        )
    }
}
