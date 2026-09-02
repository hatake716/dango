package io.github.hatake716.dango.ui.browser.components

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import io.github.hatake716.dango.ui.theme.DangoTheme
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

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
 * 領域外に出ても取り消さない長押し検出。
 * 標準の awaitLongPressOrCancellation はポインタがノード境界（＋拡張タッチ領域）を
 * 出た時点で取り消すため、高さ 32dp のリスト行では指の自然なブレ（接地点のロール）で
 * 長押しが不成立になり「リスト表示で長押しドラッグが始まらない」原因になる。
 * ここでは取り消し条件を「離した」「他の検出器がイベントを消費した（スクロール開始など）」
 * に限定する。押している行はジェスチャー開始時点で確定しているので、境界を出ても
 * 対象が変わるわけではない。
 */
private suspend fun AwaitPointerEventScope.awaitLongPressAllowingBoundsExit(
    pointerId: PointerId,
): PointerInputChange? {
    val initialDown = currentEvent.changes.firstOrNull { it.id == pointerId } ?: return null
    if (!initialDown.pressed) return null
    var currentDown = initialDown
    var longPress: PointerInputChange? = null
    return try {
        withTimeout(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.changes.all { it.changedToUpIgnoreConsumed() }) break
                if (event.changes.any { it.isConsumed }) break
                // 祖先（スクロール等）の消費は Main の後の Final パスで見える
                val finalCheck = awaitPointerEvent(PointerEventPass.Final)
                if (finalCheck.changes.any { it.isConsumed }) break
                val current = event.changes.firstOrNull { it.id == currentDown.id }
                if (current == null || !current.pressed) {
                    // 対象ポインタだけが上がった。他に押下中があれば引き継ぐ（標準実装と同じ）
                    currentDown = event.changes.firstOrNull { it.pressed } ?: break
                    longPress = currentDown
                } else {
                    longPress = current
                }
            }
            null
        }
    } catch (_: PointerEventTimeoutCancellationException) {
        val result = longPress ?: initialDown
        // 待機中の一時的な領域外は許容するが、成立時点で領域（＋拡張タッチ余白）の外に
        // いる場合は不成立にする。隣の行やグリッドの隣セルへ滑ったまま長押しが成立すると、
        // ドラッグシャドウとドロップが指の真下＝別の項目に向かってしまうため
        if (result.isOutOfBounds(size, extendedTouchPadding)) null else result
    }
}

/**
 * ドラッグ元。タッチ／スタイラスは長押しで、マウスは Finder 同様プレス＋ムーブで
 * 即ドラッグ開始（マウスで 400ms 静止し続ける操作は非現実的なため）。
 * transferKeys が null を返すとドラッグしない。
 *
 * 新 API の dragAndDropSource(transferData) は使えない：既定の開始検出が
 * detectTapGestures（未消費の down が必須で、down を consume する）のため、
 * 同じアイテムの combinedClickable と共存できない。カスタム検出を書けるレガシー API で
 * 何も消費しない検出を実装する（deprecated だが 1.9.3 に現存、代替は検出器非公開）。
 *
 * 必ず combinedClickable より後（内側）に付けること。Main パスは内側が先に受けるので
 * 未消費の down だけを対象にでき、子コントロール（リストの展開シェブロン等）や
 * 右クリック（Initial パスで consume 済み）の down からドラッグが始まらない。
 *
 * レガシー実装はハンドラのラムダを初回のものしか実行しないため、
 * 呼び出し側の最新のラムダは rememberUpdatedState 経由で参照する。
 *
 * @param onMouseDragStart マウスのプレス＋ムーブでドラッグが始まる直前に呼ばれる。
 *   タッチでは長押し時に longPressObserver 側が選択を整えるが、マウスは長押しを
 *   経ないため、ここで選択を整えて（未選択なら単独選択に切り替えて）から
 *   transferKeys を評価し、表示とペイロードを一致させる
 */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("DEPRECATION")
@Composable
fun Modifier.entryDragSource(
    onMouseDragStart: (() -> Unit)? = null,
    transferKeys: () -> Set<String>?,
): Modifier {
    val currentKeys = rememberUpdatedState(transferKeys)
    val currentMouseStart = rememberUpdatedState(onMouseDragStart)
    val shadowColor = DangoTheme.colors.selectionFocused
    // 既定のドラッグシャドウ（decoration 引数なしのオーバーロード）は使わない。
    // 既定実装はノードの描画全体を「録画済み GraphicsLayer の再生」に差し替える
    // （シャドウ用キャッシュ。foundation の CacheDrawScopeDragShadowCallback）。
    // Android 17 ではレイアウト変更後にこのキャッシュが再録画されず、列幅ドラッグ中に
    // 行の描画だけが静止する。シャドウを自前の軽量描画にすればキャッシュ層自体が
    // 作られず、内容は通常経路で描画される
    return dragAndDropSource(
        drawDragDecoration = {
            drawRoundRect(
                color = shadowColor.copy(alpha = 0.35f),
                cornerRadius = CornerRadius(8.dp.toPx()),
            )
        },
        block = {
        awaitEachGesture {
            val down = awaitFirstDown()
            // 副・中ボタンのドラッグは対象外（右クリックは onRightClick が Initial パスで
            // 消費済みだが、コンテキストメニュー表示中の押下等はここまで届き得る）。
            // isPrimaryPressed の肯定形にしないのは、テスト注入イベント（buttonState=0）でも
            // 動くようにするため
            val sideButton = currentEvent.buttons.isSecondaryPressed ||
                currentEvent.buttons.isTertiaryPressed
            when {
                down.type == PointerType.Mouse && !sideButton -> {
                    // ドラッグ開始の閾値は touchSlop
                    // （約8dp）だとマウスには大きすぎて開始が重く、閾値未満の
                    // ドラッグ未遂がクリック扱いになるため、数px 相当の専用値にする。
                    // 閾値超えの移動はイベントを消費してスクロール等より優先する
                    val mouseSlop = 2.dp.toPx()
                    var drag: PointerInputChange? = null
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed || change.isConsumed) break
                        if ((change.position - down.position).getDistance() > mouseSlop) {
                            drag = change
                            break
                        }
                    }
                    if (drag != null) {
                        // 選択整合が先、ペイロード確定が後（逆にすると
                        // 「表示は単独選択なのに直前の選択も一緒に移動」になる）
                        currentMouseStart.value?.invoke()
                        val keys = currentKeys.value()
                        if (!keys.isNullOrEmpty()) {
                            drag.consume()
                            startTransfer(Dnd.transferData(keys))
                        }
                    }
                }
                down.type == PointerType.Mouse -> {
                    // 中ボタン・戻る/進むボタン等ではドラッグしない
                }
                else -> {
                    val longPress = awaitLongPressAllowingBoundsExit(down.id)
                    if (longPress != null) {
                        val keys = currentKeys.value()
                        if (!keys.isNullOrEmpty()) startTransfer(Dnd.transferData(keys))
                    }
                }
            }
        }
        },
    )
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
            // マウスは対象外: マウスのドラッグは entryDragSource のプレス＋ムーブ経路が
            // 担い（選択整合は onMouseDragStart）、静止長押し→離すはクリックとして扱う。
            // ここで発火させると直後の onClick のトグルと打ち消し合い
            // 「選択モードに入ったのに何も選択されていない」状態になる
            if (down.type == PointerType.Mouse) return@awaitEachGesture
            val longPress = awaitLongPressAllowingBoundsExit(down.id)
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
