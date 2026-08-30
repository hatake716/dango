package io.github.hatake716.dango.ui.browser.components

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent

/**
 * ドラッグ&ドロップの共通実装（SPEC §6.3, §4.3, §4.5, §5）。
 * ClipData のラベルで dango 内のドラッグだけを受け付ける。
 */
object Dnd {
    const val LABEL = "dango-entries"

    fun transferData(keys: Set<String>): DragAndDropTransferData =
        DragAndDropTransferData(
            ClipData.newPlainText(LABEL, keys.joinToString("\n")),
        )

    fun isOurs(event: DragAndDropEvent): Boolean =
        event.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            event.toAndroidDragEvent().clipDescription?.label == LABEL

    fun keysOf(event: DragAndDropEvent): Set<String> {
        val clip = event.toAndroidDragEvent().clipData ?: return emptySet()
        if (clip.itemCount == 0) return emptySet()
        return clip.getItemAt(0).text?.split('\n')?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()
    }
}

/**
 * ドラッグ元（長押しで開始）。transferKeys が null を返すとドラッグしない。
 *
 * 新 API の dragAndDropSource(transferData) は使えない：既定の開始検出が
 * detectTapGestures（未消費の down が必須で、down を consume する）のため、
 * 同じアイテムの combinedClickable と共存できない。カスタム検出を書けるレガシー API で
 * 何も消費しない長押し検出を実装する（deprecated だが 1.9.3 に現存、代替は検出器非公開）。
 *
 * 必ず combinedClickable より後（内側）に付けること。Main パスは内側が先に受けるので
 * 未消費の down だけを対象にでき、子コントロール（リストの展開シェブロン等）や
 * 右クリック（Initial パスで consume 済み）の down からドラッグが始まらない。
 *
 * レガシー実装はハンドラのラムダを初回のものしか実行しないため、
 * 呼び出し側の最新の transferKeys は rememberUpdatedState 経由で参照する。
 */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("DEPRECATION")
@Composable
fun Modifier.entryDragSource(
    transferKeys: () -> Set<String>?,
): Modifier {
    val currentKeys = rememberUpdatedState(transferKeys)
    // 新旧 API が同名のためラムダだけでは曖昧になる。名前付き引数でレガシー版を選ぶ
    return dragAndDropSource(block = {
        awaitEachGesture {
            val down = awaitFirstDown()
            val longPress = awaitLongPressOrCancellation(down.id)
            if (longPress != null) {
                val keys = currentKeys.value()
                if (!keys.isNullOrEmpty()) startTransfer(Dnd.transferData(keys))
            }
        }
    })
}

/**
 * イベントを消費しない長押し観測。
 * combinedClickable の onLongClick は発火後にアップまで全イベントを consume するため、
 * 同じアイテムの entryDragSource（長押し→移動でドラッグ開始）が動けなくなる。
 * こちらは何も消費しないので「長押しで選択、そのまま動かすとドラッグ」が両立する。
 *
 * entryDragSource と同じく combinedClickable より後（内側）に付けること。
 * クリックはドラッグ開始時のタッチキャンセルで打ち切られる前提なので、
 * これを使うのはドラッグが必ず始まる（transferKeys が非 null の）文脈に限る。
 * ドラッグ不可の文脈では従来どおり combinedClickable の onLongClick を使うこと
 * （でないと長押し→離すで onClick が発火してしまう）。
 */
@Composable
fun Modifier.longPressObserver(onLongPress: () -> Unit): Modifier {
    val current = rememberUpdatedState(onLongPress)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val longPress = awaitLongPressOrCancellation(down.id)
            if (longPress != null) current.value()
            // 以降のイベントには触れない（ドラッグ検出器・クリック処理に委ねる）
        }
    }
}

/** ドロップ先。onHover はドロップ可能なドラッグが出入りしたときに呼ばれる（§5: 青枠ハイライト） */
@Composable
fun Modifier.entryDropTarget(
    enabled: Boolean,
    onHover: (Boolean) -> Unit,
    onDropKeys: (Set<String>) -> Unit,
): Modifier {
    // enabled で modifier を脱着するとドラッグ中の exit/end が届かず
    // ハイライトが固着するため、常に装着してコールバック側でゲートする。
    // ラムダは毎コンポジション作り直されるため、target 自体は安定させて中身だけ更新する
    val currentEnabled = rememberUpdatedState(enabled)
    val currentOnHover = rememberUpdatedState(onHover)
    val currentOnDrop = rememberUpdatedState(onDropKeys)
    LaunchedEffect(enabled) {
        if (!enabled) onHover(false)
    }
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                currentOnHover.value(true)
            }

            override fun onExited(event: DragAndDropEvent) {
                currentOnHover.value(false)
            }

            override fun onEnded(event: DragAndDropEvent) {
                currentOnHover.value(false)
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                currentOnHover.value(false)
                if (!currentEnabled.value) return false
                val keys = Dnd.keysOf(event)
                if (keys.isEmpty()) return false
                currentOnDrop.value(keys)
                return true
            }
        }
    }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { currentEnabled.value && Dnd.isOurs(it) },
        target = target,
    )
}

/** ドラッグ終了の観測用（ドラッグ元の半透明表示を解除する） */
@Composable
fun Modifier.dragEndTracker(onEnded: () -> Unit): Modifier {
    val currentOnEnded = rememberUpdatedState(onEnded)
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean = false

            override fun onEnded(event: DragAndDropEvent) {
                currentOnEnded.value()
            }
        }
    }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { Dnd.isOurs(it) },
        target = target,
    )
}
