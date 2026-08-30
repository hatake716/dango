package io.github.hatake716.dango.ui.browser.components

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
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

/** ドラッグ元（長押しで開始）。transferKeys が null を返すとドラッグしない */
fun Modifier.entryDragSource(
    transferKeys: () -> Set<String>?,
): Modifier = dragAndDropSource { _ ->
    transferKeys()?.takeIf { it.isNotEmpty() }?.let { Dnd.transferData(it) }
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
