package io.github.hatake716.dango.ui.browser.components

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.domain.model.FsPath
import io.github.hatake716.dango.ui.theme.DangoMotion
import io.github.hatake716.dango.ui.theme.DangoTheme
import io.github.hatake716.dango.ui.util.formatDateTime
import io.github.hatake716.dango.ui.util.formatSize
import io.github.hatake716.dango.ui.util.kindLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val ROW_HEIGHT = 28.dp
private val RowShape = RoundedCornerShape(5.dp)
private val COLUMN_WIDTH_WIDE = 240.dp
private const val PREVIEW_KEY = "\u0000preview"

/** 新しく右端に加わった列の出現（SPEC §5 カラム追加）: この距離だけ右から寄ってくる */
private val COLUMN_SLIDE = 24.dp
private val PREVIEW_SLIDE = 8.dp

/** 行の選択状態。青は最も深い選択だけ、祖先側の選択はグレー（Finder） */
private enum class RowSelection { NONE, FOCUSED, UNFOCUSED }

/**
 * カラム表示（SPEC §4.4: 横スクロールの複数ペイン。
 * 幅600dp未満は2列固定＋横スワイプ、末尾に選択項目のプレビュー列）。
 */
@Composable
fun ColumnView(
    basePath: FsPath,
    currentPath: FsPath,
    selection: Set<String>,
    refreshTick: Int,
    loadChildren: suspend (FsPath) -> List<FsEntry>,
    onNavigate: (FsPath) -> Unit,
    onTapFile: (FsEntry) -> Unit,
    selectionMode: Boolean = false,
) {
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
    val chainKeys = remember(chain) { chain.map { it.key } }
    // 列の中身のキャッシュ（横スワイプで列が作り直されても空白を挟まない）。
    // 表示中の列だけ残し、訪れたフォルダの一覧を溜め込まない
    val paneCache = remember { mutableStateMapOf<String, List<FsEntry>>() }
    LaunchedEffect(chainKeys) {
        val keep = chainKeys.toHashSet()
        paneCache.keys.retainAll(keep)
    }
    // プレビュー列（SPEC §4.4）は現在フォルダの単独選択ファイルにだけ出す。
    // フォルダ移動・選択解除で自然に消える
    val preview = selection.singleOrNull()?.let { key ->
        paneCache[currentPath.key]?.firstOrNull { it.path.key == key && !it.isDir }
    }
    val appear = remember { ColumnAppearTracker() }
    remember(chainKeys) { appear.update(chainKeys) }
    // プレビュー列が新たに出たときだけフェードインさせる（横スワイプで作り直されたときは動かさない）
    val previewFresh = remember { booleanArrayOf(false) }
    remember(preview == null) { previewFresh[0] = preview != null }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds()) {
        val narrow = maxWidth < 600.dp
        val columnWidth = if (narrow) maxWidth / 2 else COLUMN_WIDTH_WIDE
        val columnPx = with(LocalDensity.current) { columnWidth.roundToPx() }
        val listState = rememberLazyListState()
        val itemCount = chain.size + if (preview != null) 1 else 0
        var revealed by remember { mutableStateOf(false) }
        val changeKey = Triple(itemCount, chainKeys.last(), preview?.path?.key)
        // 列が減ると末尾側のスクロール位置は同じフレームで端まで詰められ、列が一気に横へ飛ぶ。
        // その量を先に求め（合成時点ではまだ旧レイアウト）、列全体の横移動で埋め戻してから右へ滑らせる
        var clampShift by remember { mutableFloatStateOf(0f) }
        val predictedClamp = remember(changeKey) {
            Snapshot.withoutReadObservation {
                val before = listState.firstVisibleItemIndex * columnPx +
                    listState.firstVisibleItemScrollOffset
                val maxScroll = (itemCount * columnPx - constraints.maxWidth).coerceAtLeast(0)
                (before - maxScroll).coerceAtLeast(0)
            }
        }
        val clampApplied = remember(changeKey) { booleanArrayOf(false) }
        SideEffect {
            if (!clampApplied[0]) {
                clampApplied[0] = true
                if (revealed && predictedClamp > 0) clampShift -= predictedClamp
            }
        }
        // 右端の列が画面に収まるまでスクロール（SPEC §5: 200ms）。最初の表示は即時
        LaunchedEffect(changeKey) {
            if (clampShift != 0f) {
                launch {
                    animate(clampShift, 0f, animationSpec = DangoMotion.columnAdd()) { v, _ ->
                        clampShift = v
                    }
                }
            }
            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it == itemCount }
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            if (lastVisible != null) {
                // 列はすべて同じ幅なので、未配置の新しい列の右端も計算できる
                val lastEnd = lastVisible.offset +
                    (itemCount - lastVisible.index) * lastVisible.size
                val distance = (lastEnd - info.viewportEndOffset).toFloat()
                if (distance > 0.5f) {
                    if (revealed) {
                        listState.animateScrollBy(distance, DangoMotion.columnAdd())
                    } else {
                        listState.scrollToItem(itemCount - 1)
                    }
                }
            }
            revealed = true
        }
        LazyRow(
            state = listState,
            // 縦持ちの2列固定は列単位で止める（SPEC §4.4）
            flingBehavior = if (narrow) {
                rememberSnapFlingBehavior(listState, SnapPosition.Start)
            } else {
                ScrollableDefaults.flingBehavior()
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = clampShift },
        ) {
            items(chain.size, key = { chainKeys[it] }) { index ->
                val path = chain[index]
                val isLast = index == chain.lastIndex
                val slide = remember(path.key) {
                    Animatable(if (appear.consume(path.key)) 0f else 1f)
                }
                LaunchedEffect(slide) { slide.animateTo(1f, DangoMotion.columnAdd()) }
                val slidePx = with(LocalDensity.current) { COLUMN_SLIDE.toPx() }
                // 消える列は即座に消す（フェードアウトを残すと入れ替わる列と文字が重なる）
                Row(
                    modifier = Modifier
                        .width(columnWidth)
                        .fillMaxHeight()
                        .graphicsLayer {
                            alpha = slide.value
                            translationX = (1f - slide.value) * slidePx
                        },
                ) {
                    ColumnPane(
                        path = path,
                        chainChildKey = chainKeys.getOrNull(index + 1),
                        // 選択が無ければ最後に開いたフォルダの行が最も深い選択
                        chainFocused = index == chain.lastIndex - 1 && selection.isEmpty(),
                        selection = if (isLast) selection else emptySet(),
                        refreshTick = refreshTick,
                        cache = paneCache,
                        loadChildren = loadChildren,
                        onEntryTap = { entry ->
                            if (entry.isDir) {
                                onNavigate(entry.path)
                            } else if (entry.path.key == preview?.path?.key) {
                                // プレビュー中（選択中）のファイルをもう一度タップ: 通常は開き、
                                // 選択モードでは選択を外す（どちらもタップの共通処理が担う）
                                onTapFile(entry)
                            } else {
                                // 手前の列のファイルなら、その列まで畳んでからプレビューする（Finder）。
                                // 選択モード中は畳むと選択が失われるため何もしない
                                val parent = entry.path.parent
                                val inEarlierColumn = parent != null && parent.key != currentPath.key
                                if (!(inEarlierColumn && selectionMode)) {
                                    if (inEarlierColumn) onNavigate(parent!!)
                                    onTapFile(entry)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    VerticalDivider(thickness = 0.5.dp, color = DangoTheme.colors.divider)
                }
            }
            preview?.let { entry ->
                item(key = PREVIEW_KEY) {
                    val shown = remember {
                        Animatable(if (previewFresh[0]) 0f else 1f).also { previewFresh[0] = false }
                    }
                    LaunchedEffect(Unit) { shown.animateTo(1f, DangoMotion.fade()) }
                    val slidePx = with(LocalDensity.current) { PREVIEW_SLIDE.toPx() }
                    PreviewPane(
                        entry = entry,
                        modifier = Modifier
                            .width(columnWidth)
                            .fillMaxHeight()
                            .graphicsLayer {
                                alpha = shown.value
                                translationX = (1f - shown.value) * slidePx
                            },
                    )
                }
            }
        }
    }
}

/**
 * 右端に新しく加わった列を記録し、最初に配置されたときだけスライドインさせる。
 * 横スワイプで列が作り直されたとき・表示の切り替え直後・無関係な場所へ移動したときは動かさない
 */
private class ColumnAppearTracker {
    private var shown: List<String>? = null
    private val pending = HashSet<String>()

    fun update(keys: List<String>) {
        val prev = shown
        pending.clear()
        if (prev != null && prev.firstOrNull() == keys.firstOrNull()) {
            val old = prev.toHashSet()
            keys.filterTo(pending) { it !in old }
        }
        shown = keys
    }

    fun consume(key: String): Boolean = pending.remove(key)
}

@Composable
private fun ColumnPane(
    path: FsPath,
    chainChildKey: String?,
    chainFocused: Boolean,
    selection: Set<String>,
    refreshTick: Int,
    cache: MutableMap<String, List<FsEntry>>,
    loadChildren: suspend (FsPath) -> List<FsEntry>,
    onEntryTap: (FsEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DangoTheme.colors
    val composedAt = remember(path.key) { SystemClock.uptimeMillis() }
    // refreshTick でファイル操作後に列を再読込する（VM 側でキャッシュはクリア済み）
    val entries by produceState(initialValue = cache[path.key], path.key, refreshTick) {
        val loaded = loadChildren(path)
        if (cache[path.key] != loaded) cache[path.key] = loaded
        value = loaded
    }
    // 読み込みが一瞬で済んだ列はそのまま出し、待たされたときだけフェードインする
    val contentAlpha = remember(path.key) { Animatable(if (entries != null) 1f else 0f) }
    LaunchedEffect(entries != null) {
        if (entries == null) return@LaunchedEffect
        if (SystemClock.uptimeMillis() - composedAt < FAST_LOAD_MS) {
            contentAlpha.snapTo(1f)
        } else {
            contentAlpha.animateTo(1f, DangoMotion.fade())
        }
    }
    var showSpinner by remember(path.key) { mutableStateOf(false) }
    LaunchedEffect(path.key) {
        delay(DangoMotion.SPINNER_DELAY_MS.toLong())
        showSpinner = true
    }
    val listState = rememberLazyListState()
    // 開いている子フォルダの行が見えていなければ見える位置まで送る（パスバー等で深く移動したとき）
    LaunchedEffect(chainChildKey, entries != null) {
        val list = entries ?: return@LaunchedEffect
        val key = chainChildKey ?: return@LaunchedEffect
        val index = list.indexOfFirst { it.path.key == key }
        if (index < 0) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it == list.size }
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        val fullyVisible = item != null &&
            item.offset >= info.viewportStartOffset &&
            item.offset + item.size <= info.viewportEndOffset
        if (!fullyVisible) listState.scrollToItem((index - 2).coerceAtLeast(0))
    }
    Box(modifier = modifier.fillMaxHeight()) {
        val list = entries
        if (list == null) {
            Crossfade(targetState = showSpinner, animationSpec = DangoMotion.fade(), label = "columnSpinner") { show ->
                if (show) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = colors.textSecondary,
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 4.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = contentAlpha.value },
            ) {
                items(list, key = { it.path.key }) { entry ->
                    val key = entry.path.key
                    val state = when {
                        key in selection -> RowSelection.FOCUSED
                        key == chainChildKey ->
                            if (chainFocused) RowSelection.FOCUSED else RowSelection.UNFOCUSED
                        else -> RowSelection.NONE
                    }
                    ColumnRow(
                        entry = entry,
                        state = state,
                        onClick = { onEntryTap(entry) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = DangoMotion.fade(),
                            placementSpec = DangoMotion.reorder(),
                            fadeOutSpec = DangoMotion.fade(),
                        ),
                    )
                }
            }
        }
    }
}

private const val FAST_LOAD_MS = 64L

@Composable
private fun ColumnRow(
    entry: FsEntry,
    state: RowSelection,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DangoTheme.colors
    val focused = state == RowSelection.FOCUSED
    // 付くときは素早くフェード、外れるときはさらに短く（Finder）
    val spec: FiniteAnimationSpec<Color> =
        if (state == RowSelection.NONE) DangoMotion.selectionOut() else DangoMotion.selectionIn()
    val background by animateColorAsState(
        targetValue = when (state) {
            RowSelection.FOCUSED -> colors.selectionFocused
            RowSelection.UNFOCUSED -> colors.selectionUnfocused
            RowSelection.NONE -> colors.windowBackground.copy(alpha = 0f)
        },
        animationSpec = spec,
        label = "columnRowBg",
    )
    val textColor by animateColorAsState(
        targetValue = if (focused) colors.onSelection else colors.textPrimary,
        animationSpec = spec,
        label = "columnRowText",
    )
    val chevronColor by animateColorAsState(
        targetValue = if (focused) colors.onSelection else colors.textSecondary,
        animationSpec = spec,
        label = "columnRowChevron",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .padding(horizontal = 4.dp)
            .clip(RowShape)
            .drawBehind { drawRect(background) }
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 4.dp)
            .alpha(if (entry.isRestricted) 0.45f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 種類アイコン・サムネイルは選択中も固有色のまま（Finder 同様）
        AspectFitPreview(
            entry = entry,
            width = 16.dp,
            height = 16.dp,
            iconSize = 16.dp,
            placeholderIcon = true,
            frameModifier = Modifier.registerItemBounds(entry.path.key, exact = true),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = entry.name,
            color = textColor,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
        if (entry.isDir) {
            Spacer(Modifier.width(4.dp))
            ColumnChevron(color = { chevronColor })
        }
    }
}

/** フォルダ行の末尾の山括弧（SF Symbols の chevron.right 相当の細い線） */
@Composable
private fun ColumnChevron(color: () -> Color) {
    Canvas(modifier = Modifier.size(width = 8.dp, height = 12.dp)) {
        val w = 3.6.dp.toPx()
        val h = 7.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val path = Path().apply {
            moveTo(cx - w / 2f, cy - h / 2f)
            lineTo(cx + w / 2f, cy)
            lineTo(cx - w / 2f, cy + h / 2f)
        }
        drawPath(
            path = path,
            color = color(),
            style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun PreviewPane(entry: FsEntry, modifier: Modifier = Modifier) {
    val colors = DangoTheme.colors
    BoxWithConstraints(modifier = modifier.fillMaxHeight()) {
        val imageWidth = maxWidth - 32.dp
        val imageHeight = min(imageWidth * 0.75f, maxHeight * 0.45f)
        // 同じ列の別ファイルへ切り替えたときは内容だけ素早く入れ替える
        Crossfade(targetState = entry, animationSpec = DangoMotion.fade(), label = "columnPreview") { e ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 20.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(imageHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    AspectFitPreview(
                        entry = e,
                        width = imageWidth,
                        height = imageHeight,
                        iconSize = 128.dp,
                        shadowElevation = 4.dp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = e.name,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = kindLabel(e) + " — " + formatSize(e.size),
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(thickness = 0.5.dp, color = colors.divider)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.info_title),
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                InfoRow(stringResource(R.string.info_modified), formatDateTime(e.lastModified))
                InfoRow(stringResource(R.string.info_size), formatSize(e.size))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = DangoTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = colors.textSecondary,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            maxLines = 1,
        )
        Text(
            text = value,
            color = colors.textPrimary,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}
