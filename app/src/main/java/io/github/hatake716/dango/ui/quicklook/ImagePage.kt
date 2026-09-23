package io.github.hatake716.dango.ui.quicklook

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.ui.theme.DangoMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

private const val DOUBLE_TAP_SCALE = 2.5f
private const val MAX_SCALE = 8f

/** ピンチで等倍より縮められる下限（離すと等倍へ戻る） */
private const val MIN_PINCH_SCALE = 0.8f

/** 端を越えてパンしたときの抵抗 */
private const val EDGE_RESISTANCE = 0.4f

/**
 * 画像プレビュー（SPEC §6.5: ピンチズーム・ダブルタップ拡大 250ms）。
 * 等倍での縦ドラッグ（下スワイプで閉じる）はホスト側が扱うため、ここでは奪わない
 */
@Composable
fun ImagePage(
    entry: FsEntry,
    onZoomChanged: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val motion = LocalQuickLookMotion.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var container by remember { mutableStateOf(IntSize.Zero) }
    var intrinsic by remember { mutableStateOf(Size.Unspecified) }
    val animation = remember { arrayOfNulls<Job>(1) }

    // 開いた画像の読み込みを待ってから拡大を始めてもらう
    remember(entry.path.key) { motion?.hold(entry.path.key) }
    DisposableEffect(entry.path.key) {
        onDispose { motion?.ready(entry.path.key) }
    }

    val zoomed by remember { derivedStateOf { scale > 1.02f } }
    LaunchedEffect(zoomed) { onZoomChanged(zoomed) }

    /** 等倍（Fit）で表示したときの画像の大きさ */
    fun fittedSize(): Size {
        val cw = container.width.toFloat()
        val ch = container.height.toFloat()
        val src = intrinsic
        if (src == Size.Unspecified || src.width <= 0f || src.height <= 0f) return Size(cw, ch)
        val fit = min(cw / src.width, ch / src.height)
        return Size(src.width * fit, src.height * fit)
    }

    /** 倍率 [s] のときに移動できる量（中心からの片側） */
    fun limit(s: Float): Offset {
        val fitted = fittedSize()
        return Offset(
            ((fitted.width * s - container.width) / 2f).coerceAtLeast(0f),
            ((fitted.height * s - container.height) / 2f).coerceAtLeast(0f),
        )
    }

    fun clamp(o: Offset, s: Float): Offset {
        val l = limit(s)
        return Offset(o.x.coerceIn(-l.x, l.x), o.y.coerceIn(-l.y, l.y))
    }

    fun runAnimation(block: suspend () -> Unit) {
        animation[0]?.cancel()
        animation[0] = scope.launch { block() }
    }

    /** 倍率と位置を 1 本の進捗で同時に動かす（拡大中に中心がずれない） */
    suspend fun animateTo(targetScale: Float, targetOffset: Offset) {
        val s0 = scale
        val o0 = offset
        animate(0f, 1f, animationSpec = DangoMotion.zoom()) { v, _ ->
            scale = lerp(s0, targetScale, v)
            offset = lerp(o0, targetOffset, v)
        }
    }

    /** 指を離したあと: 等倍未満は戻し、端を越えていれば戻し、それ以外は慣性で流す */
    fun settle(velocity: Velocity) {
        runAnimation {
            when {
                scale < 1f -> animateTo(1f, Offset.Zero)
                clamp(offset, scale) != offset -> animateTo(scale, clamp(offset, scale))
                velocity != Velocity.Zero && scale > 1.02f -> coroutineScope {
                    val l = limit(scale)
                    launch {
                        AnimationState(offset.x, velocity.x).animateDecay(decay) {
                            val x = value.coerceIn(-l.x, l.x)
                            offset = offset.copy(x = x)
                            if (x != value) cancelAnimation()
                        }
                    }
                    launch {
                        AnimationState(offset.y, velocity.y).animateDecay(decay) {
                            val y = value.coerceIn(-l.y, l.y)
                            offset = offset.copy(y = y)
                            if (y != value) cancelAnimation()
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { container = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        // タップ位置を中心にズーム（SPEC §5: 250ms）。タップした点が指の下に残る
                        runAnimation {
                            if (scale > 1.02f) {
                                animateTo(1f, Offset.Zero)
                            } else {
                                val center = Offset(container.width / 2f, container.height / 2f)
                                val target = clamp((center - tap) * (DOUBLE_TAP_SCALE - 1f), DOUBLE_TAP_SCALE)
                                animateTo(DOUBLE_TAP_SCALE, target)
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    var handled = false
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            // ピンチ: 2 本指の中点を固定したまま拡大縮小
                            animation[0]?.cancel()
                            handled = true
                            val center = Offset(container.width / 2f, container.height / 2f)
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val newScale = (scale * event.calculateZoom()).coerceIn(MIN_PINCH_SCALE, MAX_SCALE)
                            val z = newScale / scale
                            val d = centroid - center
                            offset = (offset - d) * z + d + event.calculatePan()
                            scale = newScale
                            event.changes.forEach { it.consume() }
                            tracker.resetTracking()
                        } else if (scale > 1.02f || handled) {
                            // 拡大中の 1 本指はパン（端の先は抵抗を付けて引ける）
                            val change = event.changes.firstOrNull { it.pressed } ?: continue
                            animation[0]?.cancel()
                            handled = true
                            val delta = change.positionChange()
                            val l = limit(scale)
                            fun resist(v: Float, dv: Float, max: Float): Float =
                                if (abs(v) > max && sign(dv) == sign(v)) dv * EDGE_RESISTANCE else dv
                            offset += Offset(resist(offset.x, delta.x, l.x), resist(offset.y, delta.y, l.y))
                            tracker.addPointerInputChange(change)
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    if (handled) settle(tracker.calculateVelocity())
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val context = LocalPlatformContext.current
        AsyncImage(
            // 出現は Quick Look の拡大が担うため、Coil のクロスフェードは重ねない
            model = remember(entry.path.key) {
                ImageRequest.Builder(context)
                    .data(entry.fileUri ?: entry.previewUri)
                    .crossfade(false)
                    .build()
            },
            contentDescription = entry.name,
            contentScale = ContentScale.Fit,
            onSuccess = { state ->
                intrinsic = state.painter.intrinsicSize
                motion?.ready(entry.path.key)
            },
            onError = { motion?.ready(entry.path.key) },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
