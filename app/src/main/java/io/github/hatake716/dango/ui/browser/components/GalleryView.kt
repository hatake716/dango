package io.github.hatake716.dango.ui.browser.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.animateScrollBy
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.ui.theme.DangoMotion
import io.github.hatake716.dango.ui.theme.DangoTheme
import io.github.hatake716.dango.ui.util.formatSize
import io.github.hatake716.dango.ui.util.kindLabel
import kotlinx.coroutines.flow.first

/** ストリップのサムネイル（Finder のギャラリー既定 48pt）とそのセル */
private val STRIP_CELL = 56.dp
private val STRIP_THUMB = 48.dp
private val STRIP_HEIGHT = 72.dp
private val STRIP_SPACING = 4.dp
private val StripShape = RoundedCornerShape(6.dp)

/** 大プレビューがストリップのサムネイルを仮表示に使うためのメモリキャッシュキー */
private fun stripCacheKey(uri: String) = "gallery-strip:$uri"

/** ギャラリー表示（SPEC §4.4: 上部に大きなプレビュー、下部にフィルムストリップ） */
@Composable
fun GalleryView(
    entries: List<FsEntry>,
    selection: Set<String>,
    onSelect: (FsEntry) -> Unit,
    onOpen: (FsEntry) -> Unit,
    onLongPress: (FsEntry) -> Unit,
) {
    val colors = DangoTheme.colors
    val selected = entries.firstOrNull { it.path.key in selection } ?: entries.firstOrNull()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.windowBackground),
    ) {
        // 選択が変わったら大プレビューをクロスフェード（わずかに拡大しながら現れる）
        AnimatedContent(
            targetState = selected,
            contentKey = { it?.path?.key },
            transitionSpec = {
                (
                    fadeIn(DangoMotion.fade()) +
                        scaleIn(DangoMotion.fade(), initialScale = GALLERY_ENTER_SCALE)
                    ).togetherWith(fadeOut(DangoMotion.fade()))
            },
            contentAlignment = Alignment.Center,
            label = "galleryPreview",
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { entry ->
            if (entry == null) return@AnimatedContent
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .combinedClickable(
                        interactionSource = null,
                        indication = null,
                        onClick = { onSelect(entry) },
                        onDoubleClick = { onOpen(entry) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AspectFitPreview(
                    entry = entry,
                    width = maxWidth,
                    height = maxHeight,
                    iconSize = min(160.dp, maxHeight * 0.4f),
                    shadowElevation = 6.dp,
                    placeholderCacheKey = entry.previewUri?.let(::stripCacheKey),
                    frameModifier = Modifier.registerItemBounds(entry.path.key, exact = true),
                )
            }
        }
        selected?.let { entry ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    text = entry.name,
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    text = if (entry.isDir) {
                        kindLabel(entry)
                    } else {
                        kindLabel(entry) + " · " + formatSize(entry.size)
                    },
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(thickness = 0.5.dp, color = colors.divider)
        FilmStrip(
            entries = entries,
            selection = selection,
            selectedKey = selected?.path?.key,
            onSelect = onSelect,
            onOpen = onOpen,
            onLongPress = onLongPress,
        )
    }
}

private const val GALLERY_ENTER_SCALE = 0.98f

@Composable
private fun FilmStrip(
    entries: List<FsEntry>,
    selection: Set<String>,
    selectedKey: String?,
    onSelect: (FsEntry) -> Unit,
    onOpen: (FsEntry) -> Unit,
    onLongPress: (FsEntry) -> Unit,
) {
    val colors = DangoTheme.colors
    val stripState = rememberLazyListState()
    val selectedIndex = entries.indexOfFirst { it.path.key == selectedKey }
    var positioned by remember { mutableStateOf(false) }
    // 選択サムネイルをストリップの中央へ（両端はリストの端で止まる）
    LaunchedEffect(selectedKey, selectedIndex, entries.size) {
        if (selectedIndex < 0) return@LaunchedEffect
        snapshotFlow { stripState.layoutInfo.totalItemsCount }.first { it == entries.size }
        val info = stripState.layoutInfo
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        if (viewport <= 0) return@LaunchedEffect
        val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
        val visible = info.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
        if (positioned && visible != null) {
            // 端では動ける分だけに抑える（越えた分で動きが途中で止まって見えないように）
            var delta = visible.offset + visible.size / 2f - center
            val first = info.visibleItemsInfo.first()
            val last = info.visibleItemsInfo.last()
            if (first.index == 0) delta = maxOf(delta, minOf(0, first.offset).toFloat())
            if (last.index == info.totalItemsCount - 1) {
                val room = last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)
                delta = minOf(delta, maxOf(0, room).toFloat())
            }
            if (kotlin.math.abs(delta) >= 1f) stripState.animateScrollBy(delta, DangoMotion.reorder())
        } else {
            val itemPx = visible?.size ?: info.visibleItemsInfo.firstOrNull()?.size ?: 0
            val offset = -(viewport / 2 - itemPx / 2)
            if (positioned) {
                stripState.animateScrollToItem(selectedIndex, offset)
            } else {
                stripState.scrollToItem(selectedIndex, offset)
            }
        }
        positioned = true
    }
    LazyRow(
        state = stripState,
        contentPadding = PaddingValues(horizontal = 8.dp),
        // 少数のときは中央に寄せる（Finder のストリップ）
        horizontalArrangement = Arrangement.spacedBy(STRIP_SPACING, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT)
            .background(colors.windowBackground),
    ) {
        items(entries, key = { it.path.key }) { entry ->
            val key = entry.path.key
            val isCurrent = key == selectedKey
            StripItem(
                entry = entry,
                isCurrent = isCurrent,
                isSelected = key in selection,
                modifier = Modifier
                    .animateItem(
                        fadeInSpec = DangoMotion.fade(),
                        placementSpec = DangoMotion.reorder(),
                        fadeOutSpec = DangoMotion.fade(),
                    )
                    // 表示中の項目は大プレビューを Quick Look の起点にする
                    .then(if (isCurrent) Modifier else Modifier.registerItemBounds(key)),
                // ダブルタップ判定を待たずに即座に切り替える。表示中の単独選択をもう一度タップで開く
                onClick = {
                    if (isCurrent && selection.size == 1 && key in selection) onOpen(entry) else onSelect(entry)
                },
                onLongClick = { onLongPress(entry) },
            )
        }
    }
}

@Composable
private fun StripItem(
    entry: FsEntry,
    isCurrent: Boolean,
    isSelected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = DangoTheme.colors
    val on = isCurrent || isSelected
    val spec: FiniteAnimationSpec<Color> =
        if (on) DangoMotion.selectionIn() else DangoMotion.selectionOut()
    val backing by animateColorAsState(
        targetValue = if (on) colors.selectionUnfocused else colors.selectionUnfocused.copy(alpha = 0f),
        animationSpec = spec,
        label = "stripBacking",
    )
    val ring by animateColorAsState(
        targetValue = if (isCurrent) colors.selectionFocused else colors.selectionFocused.copy(alpha = 0f),
        animationSpec = spec,
        label = "stripRing",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(STRIP_CELL)
            .clip(StripShape)
            .drawBehind {
                val r = CornerRadius(6.dp.toPx())
                drawRoundRect(backing, cornerRadius = r)
                val w = 2.dp.toPx()
                drawRoundRect(
                    color = ring,
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(r.x - w / 2),
                    style = Stroke(w),
                )
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        AspectFitPreview(
            entry = entry,
            width = STRIP_THUMB,
            height = STRIP_THUMB,
            iconSize = 40.dp,
            memoryCacheKey = entry.previewUri?.let(::stripCacheKey),
            placeholderIcon = true,
        )
    }
}

/**
 * 画像を縦横比を保って [width]×[height] に収め、実際に絵が占める矩形にだけ影と
 * 細い縁を付ける（Finder のプレビュー・サムネイル）。絵が無い・読めないときは種類アイコン。
 * 読み込み中のサイズ変化で再デコードさせないため、画像自体は常に枠いっぱいに配置する
 */
@Composable
internal fun AspectFitPreview(
    entry: FsEntry,
    width: Dp,
    height: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    shadowElevation: Dp = 0.dp,
    border: Boolean = true,
    memoryCacheKey: String? = null,
    placeholderCacheKey: String? = null,
    placeholderIcon: Boolean = false,
    frameModifier: Modifier = Modifier,
) {
    val colors = DangoTheme.colors
    val uri = entry.previewUri
    var failed by remember(uri) { mutableStateOf(false) }
    var ratio by remember(uri) { mutableFloatStateOf(0f) }
    Box(modifier = modifier.size(width, height), contentAlignment = Alignment.Center) {
        if (uri == null || failed) {
            EntryKindIcon(
                kind = entry.kind,
                name = entry.name,
                size = min(iconSize, min(width, height)),
                modifier = frameModifier,
            )
            return@Box
        }
        val frame = if (ratio > 0f) fitInto(ratio, width, height) else null
        val frameAlpha by animateFloatAsState(
            targetValue = if (frame != null) 1f else 0f,
            animationSpec = DangoMotion.fade(),
            label = "previewFrame",
        )
        if (frame != null && shadowElevation > 0.dp) {
            Box(
                Modifier
                    .size(frame)
                    .graphicsLayer { alpha = frameAlpha }
                    .shadow(shadowElevation, RoundedCornerShape(2.dp), clip = false)
                    .background(colors.windowBackground),
            )
        }
        val context = LocalPlatformContext.current
        val request = remember(uri, memoryCacheKey, placeholderCacheKey) {
            ImageRequest.Builder(context)
                .data(uri)
                .apply {
                    memoryCacheKey?.let { memoryCacheKey(it) }
                    placeholderCacheKey?.let { placeholderMemoryCacheKey(it) }
                }
                .build()
        }
        val placeholder: Painter? = if (placeholderIcon) rememberVectorPainter(entryIcon(entry.kind)) else null
        AsyncImage(
            model = request,
            contentDescription = entry.name,
            contentScale = ContentScale.Fit,
            placeholder = placeholder,
            onLoading = { state ->
                // 仮表示（ストリップのサムネイル）の縦横比で先に枠を出す。アイコンの仮表示は除く
                if (!placeholderIcon) state.painter?.let { ratio = aspectOf(it, ratio) }
            },
            onSuccess = { state -> ratio = aspectOf(state.painter, ratio) },
            onError = { failed = true },
            modifier = Modifier.size(width, height),
        )
        if (frame != null) {
            Box(
                Modifier
                    .size(frame)
                    .graphicsLayer { alpha = frameAlpha }
                    .then(if (border) Modifier.border(0.5.dp, colors.divider) else Modifier)
                    .then(frameModifier),
            )
        }
    }
}

private fun aspectOf(painter: Painter, fallback: Float): Float {
    val s = painter.intrinsicSize
    return if (s.isSpecified && s.width > 0f && s.height > 0f) s.width / s.height else fallback
}

private fun fitInto(ratio: Float, width: Dp, height: Dp): DpSize =
    if (ratio >= width / height) {
        DpSize(width, width / ratio)
    } else {
        DpSize(height * ratio, height)
    }
