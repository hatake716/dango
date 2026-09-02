package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.max
import kotlin.math.min

/**
 * ラバーバンド選択（SPEC §6.2）の共有状態。
 * アイテム境界はコンテナ座標系で保持し、表示中のものだけが残る
 */
class MarqueeState {
    /** コンテナ左上のルート座標（アイテム境界の座標変換用） */
    var containerOrigin = Offset.Zero

    /** 表示中アイテムの境界（key → コンテナ座標の矩形） */
    val itemBounds = HashMap<String, Rect>()

    /** ドラッグ中の選択矩形（null なら非表示） */
    var rect by mutableStateOf<Rect?>(null)
}

@Composable
fun rememberMarqueeState(): MarqueeState = remember { MarqueeState() }

/** リスト/グリッドのコンテナに付ける: 座標原点を記録する */
fun Modifier.marqueeContainer(state: MarqueeState): Modifier =
    onGloballyPositioned { state.containerOrigin = it.positionInRoot() }

/** 各アイテムに付ける: 自分の境界を登録し、破棄時に取り除く */
@Composable
fun Modifier.marqueeItemBounds(state: MarqueeState, key: String): Modifier {
    DisposableEffect(state, key) {
        onDispose { state.itemBounds.remove(key) }
    }
    return onGloballyPositioned { c ->
        val p = c.positionInRoot() - state.containerOrigin
        state.itemBounds[key] = Rect(p, c.size.toSize())
    }
}

/**
 * コンテナに付ける: マウス主ボタンで空白領域からドラッグすると選択矩形を描き、
 * 交差するアイテムを選択する（Finder のラバーバンド選択）。
 * Ctrl / Cmd / Shift を押しながらなら既存の選択に追加する。
 * タッチには反応しない（スクロールと競合するため。タッチは長押し選択モードを使う）。
 */
@Composable
fun Modifier.marqueeSelectSource(
    state: MarqueeState,
    enabled: () -> Boolean,
    currentSelection: () -> Set<String>,
    onSelect: (Set<String>) -> Unit,
): Modifier {
    val enabledNow = rememberUpdatedState(enabled)
    val selectionNow = rememberUpdatedState(currentSelection)
    val onSelectNow = rememberUpdatedState(onSelect)
    return pointerInput(state) {
        awaitEachGesture {
            // Initial パスで先取りする: ラバーバンド成立後は自分でイベントを消費して
            // スクロール等に渡さないため（成立前のクリックはそのまま通す）
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type != PointerType.Mouse) return@awaitEachGesture
            if (!enabledNow.value()) return@awaitEachGesture
            val buttons = currentEvent.buttons
            if (buttons.isSecondaryPressed || buttons.isTertiaryPressed) return@awaitEachGesture
            // アイテムの上からは始めない（アイテム自体のクリック・ドラッグに委ねる）
            if (state.itemBounds.values.any { it.contains(down.position) }) return@awaitEachGesture
            val mods = currentEvent.keyboardModifiers
            val additive = mods.isCtrlPressed || mods.isMetaPressed || mods.isShiftPressed
            val base = if (additive) selectionNow.value() else emptySet()
            val start = down.position
            val slop = 4.dp.toPx()
            var active = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (!active && (change.position - start).getDistance() > slop) active = true
                if (active) {
                    change.consume()
                    // コンテナ境界にクランプ（ヘッダやツールバーへ矩形がはみ出さないように）
                    val cx = change.position.x.coerceIn(0f, size.width.toFloat())
                    val cy = change.position.y.coerceIn(0f, size.height.toFloat())
                    val r = Rect(
                        min(start.x, cx),
                        min(start.y, cy),
                        max(start.x, cx),
                        max(start.y, cy),
                    )
                    state.rect = r
                    val hits = HashSet<String>()
                    for ((key, b) in state.itemBounds) {
                        if (b.overlaps(r)) hits.add(key)
                    }
                    onSelectNow.value(base + hits)
                }
            }
            state.rect = null
        }
    }
}

/** クリック時の修飾キー状態。各アイテムが自分の down 時点で記録し、クリック確定時に参照する */
class ClickModifierState {
    var ctrl = false
    var shift = false
}

/**
 * アイテムに付ける: down 時点の修飾キーを記録する（Initial パス・非消費）。
 * combinedClickable はダブルクリック判定のため onClick を遅延させるので、
 * 画面共有のホルダーだと別クリックの修飾キーで上書きされる。アイテムごとに持つ
 */
fun Modifier.recordClickModifiers(holder: ClickModifierState): Modifier = pointerInput(holder) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val m = currentEvent.keyboardModifiers
        holder.ctrl = m.isCtrlPressed || m.isMetaPressed
        holder.shift = m.isShiftPressed
    }
}

/** コンテナ内の最前面に置く: 選択矩形の描画（塗り＋枠。SPEC §9 のアクセント色） */
@Composable
fun MarqueeOverlay(state: MarqueeState, color: Color, modifier: Modifier = Modifier) {
    val rect = state.rect ?: return
    androidx.compose.foundation.layout.Box(
        modifier = modifier.drawBehind {
            drawRect(color = color.copy(alpha = 0.12f), topLeft = rect.topLeft, size = rect.size)
            drawRect(
                color = color.copy(alpha = 0.8f),
                topLeft = rect.topLeft,
                size = rect.size,
                style = Stroke(width = 1.dp.toPx()),
            )
        },
    )
}
