package io.github.hatake716.dango.ui.quicklook

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 画像プレビュー（SPEC §6.5: ピンチズーム・ダブルタップ拡大 250ms・下スワイプでディスミス、指に追従）。
 */
@Composable
fun ImagePage(
    entry: io.github.hatake716.dango.domain.model.FsEntry,
    onDismiss: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(scale.value) {
        onZoomChanged(scale.value > 1.02f)
    }

    fun clampOffset(): Offset {
        val maxX = (containerSize.width * (scale.value - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (containerSize.height * (scale.value - 1f) / 2f).coerceAtLeast(0f)
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        // タップ位置を中心にズーム（SPEC §5: 250ms）
                        scope.launch {
                            if (scale.value > 1.02f) {
                                scale.animateTo(1f, tween(250))
                                offset = Offset.Zero
                            } else {
                                val target = 2.5f
                                val center = Offset(
                                    containerSize.width / 2f,
                                    containerSize.height / 2f,
                                )
                                offset = (center - tap) * (target - 1f)
                                scale.animateTo(target, tween(250))
                                offset = clampOffset()
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var dismissing = false
                    var totalDx = 0f
                    var totalDy = 0f
                    val slop = viewConfiguration.touchSlop
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        when {
                            event.changes.size >= 2 -> {
                                scope.launch {
                                    scale.snapTo((scale.value * zoom).coerceIn(1f, 8f))
                                }
                                offset = (offset + pan)
                                event.changes.forEach { it.consume() }
                            }
                            scale.value > 1.02f -> {
                                offset += pan
                                event.changes.forEach { it.consume() }
                            }
                            else -> {
                                // 等倍時の縦ドラッグはディスミス（指に追従）。
                                // Pager の横スワイプを奪わないよう、スロップ超過かつ明確な縦方向のみ
                                totalDx += pan.x
                                totalDy += pan.y
                                if (dismissing ||
                                    (abs(totalDy) > slop && abs(totalDy) > abs(totalDx) * 1.4f)
                                ) {
                                    dismissing = true
                                    dragY += pan.y
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    offset = clampOffset()
                    if (dismissing) {
                        if (abs(dragY) > containerSize.height * 0.18f) {
                            onDismiss()
                        } else {
                            dragY = 0f
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val dismissProgress =
            if (containerSize.height > 0) (abs(dragY) / containerSize.height).coerceIn(0f, 0.4f) else 0f
        AsyncImage(
            model = entry.fileUri ?: entry.previewUri,
            contentDescription = entry.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = scale.value * (1f - dismissProgress * 0.5f)
                    scaleX = s
                    scaleY = s
                    translationX = offset.x
                    translationY = offset.y + dragY
                    alpha = 1f - dismissProgress
                },
        )
    }
}
