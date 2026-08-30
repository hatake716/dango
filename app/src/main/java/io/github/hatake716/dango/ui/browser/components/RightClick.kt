package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * マウスの右クリック（セカンダリボタン）検出。
 *
 * アイテム側（既定）は Initial パスで消費し、同じノードの clickable / combinedClickable が
 * タップとして解釈するのを防ぐ（メニューと open() の同時発火バグ対策）。
 *
 * 背景側は pass = Main + requireUnconsumed = true で使う。
 * Initial パスは親→子の順に届くため、親も Initial で待つと子が消費する前に
 * 受け取ってしまい、アイテム上の右クリックで両方のメニューが開いてしまう。
 * Main パスは子→親の順なので、子（アイテム）が消費した右クリックを親が正しく無視できる。
 */
fun Modifier.onRightClick(
    requireUnconsumed: Boolean = false,
    pass: PointerEventPass = PointerEventPass.Initial,
    onRightClick: (Offset) -> Unit,
): Modifier = pointerInput(requireUnconsumed, pass) {
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = requireUnconsumed,
            pass = pass,
        )
        if (down.type != PointerType.Mouse || !currentEvent.buttons.isSecondaryPressed) {
            return@awaitEachGesture
        }
        down.consume()
        // ボタンが離されるまで同じパスで消費し続けてから発火する
        while (true) {
            val event = awaitPointerEvent(pass)
            event.changes.forEach { it.consume() }
            if (!event.buttons.isSecondaryPressed && event.changes.all { !it.pressed }) break
        }
        onRightClick(down.position)
    }
}

/** 右クリックを握りつぶす（clickable が左クリック扱いで発火するのを防ぐ） */
fun Modifier.swallowRightClick(): Modifier = onRightClick { }
