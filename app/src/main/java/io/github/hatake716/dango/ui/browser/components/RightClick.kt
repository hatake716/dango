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
 * Initial パスで消費することで、同じノードの clickable / combinedClickable が
 * タップとして解釈するのを防ぐ（メニューと open() の同時発火バグ対策）。
 * requireUnconsumed=true の親ハンドラは、子が consume したイベントを無視するので
 * アイテム上とその背景でメニューを出し分けられる。
 */
fun Modifier.onRightClick(
    requireUnconsumed: Boolean = false,
    onRightClick: (Offset) -> Unit,
): Modifier = pointerInput(requireUnconsumed) {
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = requireUnconsumed,
            pass = PointerEventPass.Initial,
        )
        if (down.type != PointerType.Mouse || !currentEvent.buttons.isSecondaryPressed) {
            return@awaitEachGesture
        }
        down.consume()
        // ボタンが離されるまで Initial パスで消費し続けてから発火する
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
            if (!event.buttons.isSecondaryPressed && event.changes.all { !it.pressed }) break
        }
        onRightClick(down.position)
    }
}

/** 右クリックを握りつぶす（clickable が左クリック扱いで発火するのを防ぐ） */
fun Modifier.swallowRightClick(): Modifier = onRightClick { }
