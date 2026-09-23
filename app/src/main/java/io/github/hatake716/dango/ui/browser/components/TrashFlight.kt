package io.github.hatake716.dango.ui.browser.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.ui.theme.DangoMotion

/** ゴミ箱へ吸い込まれる1項目（起点はアイテムの画面位置。ルート座標 px） */
class TrashFlight(val id: Long, val entry: FsEntry, val from: Rect)

/** アニメーションの到達点の名前（[registerAnimationTarget] で登録） */
object TrashTargets {
    /** サイドバーの「ゴミ箱」行 */
    const val SIDEBAR = "trash"

    /** 下部アクションバーの削除ボタン */
    const val BUTTON = "trash-button"
}

/**
 * 削除した項目がゴミ箱へ吸い込まれる縮小移動（SPEC §5: 300ms）。
 * 到達点はサイドバーのゴミ箱行 → 削除ボタン → 左下の順に、表示されているものを使う。
 * 項目の中心から放物線状に移動しながら縮み、終盤で消える
 */
@Composable
fun TrashFlightOverlay(
    flights: List<TrashFlight>,
    registry: ItemBoundsRegistry,
    onFinished: (TrashFlight) -> Unit,
    modifier: Modifier = Modifier,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var overlaySize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                val b = coords.boundsInRoot()
                origin = b.topLeft
                overlaySize = b.size
            },
    ) {
        flights.forEach { flight ->
            key(flight.id) {
                FlyingItem(flight, registry, origin, overlaySize, onFinished)
            }
        }
    }
}

@Composable
private fun FlyingItem(
    flight: TrashFlight,
    registry: ItemBoundsRegistry,
    origin: Offset,
    overlaySize: androidx.compose.ui.geometry.Size,
    onFinished: (TrashFlight) -> Unit,
) {
    val density = LocalDensity.current
    val progress = remember { Animatable(0f) }
    val from = flight.from
    // リスト行は横長なので、行全体ではなく先頭のアイコン位置から飛ばす
    val isRow = from.width > from.height * 3f
    val iconPx = with(density) {
        (if (isRow) 18.dp.toPx() else minOf(from.width, from.height) * 0.62f)
            .coerceIn(18.dp.toPx(), 96.dp.toPx())
    }
    val start = if (isRow) {
        Offset(from.left + with(density) { 42.dp.toPx() }, from.center.y)
    } else {
        Offset(from.center.x, from.top + from.height * 0.42f)
    }
    val fixedTarget = remember {
        registry.targets[TrashTargets.SIDEBAR]?.center ?: registry.targets[TrashTargets.BUTTON]?.center
    }
    // 到達点が画面に無いときは左下へ。オーバーレイの大きさが測れてから決まるので毎回求める
    val margin = with(density) { 28.dp.toPx() }
    val target = fixedTarget ?: Offset(origin.x + margin, origin.y + overlaySize.height - margin)
    LaunchedEffect(Unit) {
        progress.animateTo(1f, DangoMotion.trashFlight())
        registry.trashLanded.intValue++
        onFinished(flight)
    }
    // 始点と終点の中間より上に制御点を置いた二次ベジェ
    val control = Offset(
        (start.x + target.x) / 2f,
        minOf(start.y, target.y) - (target - start).getDistance() * 0.18f,
    )
    val sizeDp = with(density) { iconPx.toDp() }
    Box(
        modifier = Modifier.graphicsLayer {
            val t = progress.value
            val u = 1f - t
            val p = start * (u * u) + control * (2f * u * t) + target * (t * t)
            translationX = p.x - origin.x - iconPx / 2f
            translationY = p.y - origin.y - iconPx / 2f
            val s = 1f - 0.85f * t
            scaleX = s
            scaleY = s
            alpha = if (t < 0.6f) 1f else (1f - (t - 0.6f) / 0.4f).coerceIn(0f, 1f)
        },
    ) {
        EntryThumbnailOrIcon(
            entry = flight.entry,
            thumbSize = sizeDp,
            iconSize = sizeDp,
            shape = RoundedCornerShape(3.dp),
            fit = true,
        )
    }
}
