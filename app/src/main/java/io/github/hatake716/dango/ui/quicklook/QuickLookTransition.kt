package io.github.hatake716.dango.ui.quicklook

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import io.github.hatake716.dango.ui.browser.components.LocalItemBounds
import io.github.hatake716.dango.ui.theme.DangoMotion
import io.github.hatake716.dango.ui.theme.DarkDangoColors
import io.github.hatake716.dango.ui.theme.LocalDangoColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.min

/** 暗幕の最大の濃さ */
internal const val QL_BACKDROP_ALPHA = 0.94f

/** 起点矩形からの拡大では、進捗がここに達するまでに中身をフェードインさせる */
private const val ZOOM_FADE_SPAN = 0.25f

/** 下スワイプ: 画面高さに対するこの割合を超えて離すと閉じる */
private const val DISMISS_FRACTION = 0.18f

/** 下スワイプ: この速度（/秒）以上で下へ弾いても閉じる */
private val DISMISS_FLING = 800.dp

/** 下スワイプで画面の高さぶん引いたときの縮小量 */
private const val DRAG_SHRINK = 0.2f

/** 中身の拡大・移動。オーバーレイ中心を基準に [scale] してから平行移動する */
@Immutable
internal data class QlFrame(val scale: Float, val tx: Float, val ty: Float) {
    companion object {
        val Identity = QlFrame(1f, 0f, 0f)
    }
}

private fun lerp(a: QlFrame, b: QlFrame, t: Float) =
    QlFrame(lerp(a.scale, b.scale, t), lerp(a.tx, b.tx, t), lerp(a.ty, b.ty, t))

/**
 * 起点にする矩形。登録されるのはアイテム全体なので、横長のリスト行は行頭のアイコン付近、
 * アイコン表示のセルは名前ラベルを除いた上部の正方形（アイコン部分）に寄せる
 */
private fun iconRectOf(item: Rect, rtl: Boolean): Rect {
    if (item.width > item.height * 3f) {
        val cx = if (rtl) item.right - item.height else item.left + item.height
        return Rect(Offset(cx, item.center.y), item.height / 2f)
    }
    val side = min(item.width, item.height * 0.7f)
    return Rect(item.center.x - side / 2f, item.top, item.center.x + side / 2f, item.top + side)
}

/**
 * Quick Look の出入りと下スワイプの状態（SPEC §5）。中身だけを拡大縮小し、
 * 暗幕と上部バーは拡大に含めず別々にフェードさせる
 */
@Stable
class QuickLookMotion internal constructor() {
    /** 0 = 起点アイテムの矩形、1 = 全画面 */
    internal val progress = Animatable(0f)
    internal val backdrop = Animatable(0f)
    internal val chrome = Animatable(0f)

    /** 下スワイプで指に追従している量（px） */
    internal var dragY by mutableFloatStateOf(0f)

    /** progress = 0 の端点になるアイテムの矩形（ルート座標）。null ならフェード */
    private var sourceRect by mutableStateOf<Rect?>(null)

    /** progress = 1 の端点。開くときは等倍、閉じるときはその時点の見た目 */
    private var fullFrame by mutableStateOf(QlFrame.Identity)
    private var fallbackBase by mutableStateOf(QlFrame.Identity)

    internal var overlayBounds by mutableStateOf(Rect.Zero)

    /** ページ表示領域の上端（オーバーレイ上端からの px。上部バーの下） */
    internal var contentTop by mutableFloatStateOf(0f)
    internal var rtl = false

    internal var requested by mutableStateOf(false)
    internal var present by mutableStateOf(false)
    internal var session by mutableIntStateOf(0)
    internal var closing by mutableStateOf(false)
    internal var dismissing by mutableStateOf(false)

    internal var openingKey by mutableStateOf<String?>(null)
    internal var waitingContent by mutableStateOf(false)
    private var pendingKey by mutableStateOf<String?>(null)

    /**
     * 開いたページが読み込み完了まで拡大の開始を待たせる（上限 QUICK_LOOK_HOLD_MAX_MS）。
     * 待たせたら true
     */
    internal fun hold(key: String): Boolean {
        if (!waitingContent || key != openingKey) return false
        pendingKey = key
        return true
    }

    internal fun ready(key: String) {
        if (pendingKey == key) pendingKey = null
    }

    private fun dragFraction(): Float =
        (dragY / overlayBounds.height.coerceAtLeast(1f)).coerceIn(0f, 1f)

    internal fun backdropAlpha(): Float = backdrop.value * (1f - dragFraction() * 2f).coerceIn(0f, 1f)

    internal fun chromeAlpha(): Float = chrome.value * (1f - dragFraction() * 8f).coerceIn(0f, 1f)

    internal fun contentAlpha(): Float {
        val p = progress.value
        return if (sourceRect == null) p.coerceIn(0f, 1f) else (p / ZOOM_FADE_SPAN).coerceIn(0f, 1f)
    }

    internal fun currentFrame(): QlFrame {
        val p = progress.value
        // 開き終えたら端点そのもの（等倍にわずかな誤差の拡大が残ると画像がぼやける）
        val base = if (p == 1f) fullFrame else lerp(startFrame(), fullFrame, p)
        val d = dragY
        if (d == 0f) return base
        return QlFrame(base.scale * (1f - DRAG_SHRINK * dragFraction()), base.tx, base.ty + d)
    }

    private fun startFrame(): QlFrame {
        val item = sourceRect
        val overlay = overlayBounds
        if (item == null || overlay.width <= 0f || overlay.height <= contentTop) {
            return fallbackBase.copy(scale = fallbackBase.scale * DangoMotion.QUICK_LOOK_FALLBACK_SCALE)
        }
        // ページ領域がアイコンの矩形に収まる倍率・位置
        val area = Rect(overlay.left, overlay.top + contentTop, overlay.right, overlay.bottom)
        val src = iconRectOf(item, rtl)
        val s = min(src.width / area.width, src.height / area.height)
        val pivot = overlay.center
        val t = src.center - (pivot + (area.center - pivot) * s)
        return QlFrame(s, t.x, t.y)
    }

    internal suspend fun enter(source: Rect?) = coroutineScope {
        val reopening = closing
        closing = false
        dismissing = false
        if (reopening) {
            fullFrame = QlFrame.Identity
        } else {
            progress.snapTo(0f)
            backdrop.snapTo(0f)
            chrome.snapTo(0f)
            dragY = 0f
            fullFrame = QlFrame.Identity
            fallbackBase = QlFrame.Identity
            sourceRect = source?.takeIf { it.width > 0f && it.height > 0f }
            Log.d("dango", "QL enter from=$sourceRect")
            withFrameNanos { }
            withTimeoutOrNull(DangoMotion.QUICK_LOOK_HOLD_MAX_MS) {
                snapshotFlow { pendingKey }.first { it == null }
            }
        }
        waitingContent = false
        pendingKey = null
        launch { progress.animateTo(1f, DangoMotion.quickLook()) }
        launch { backdrop.animateTo(1f, tween(DangoMotion.QUICK_LOOK_FADE_MS)) }
        launch {
            chrome.animateTo(
                1f,
                tween(
                    DangoMotion.QUICK_LOOK_FADE_MS - DangoMotion.QUICK_LOOK_CHROME_DELAY_MS,
                    delayMillis = DangoMotion.QUICK_LOOK_CHROME_DELAY_MS,
                    easing = DangoMotion.MacEase,
                ),
            )
        }
    }

    /** 閉じる: その時点の見た目（ドラッグ中の位置を含む）から [target] へ縮小する */
    internal suspend fun exit(target: Rect?) {
        closing = true
        waitingContent = false
        pendingKey = null
        if (progress.value <= 0f) {
            // まだ拡大が始まっていない（読み込み待ち中）: 見えていないのでそのまま消す
            backdrop.snapTo(0f)
            chrome.snapTo(0f)
            closing = false
            return
        }
        val now = currentFrame()
        val backdropNow = backdropAlpha()
        val chromeNow = chromeAlpha()
        val usable = target?.takeIf {
            it.width > 0f && it.height > 0f && it.overlaps(overlayBounds)
        }
        Log.d("dango", "QL exit to=$usable")
        fullFrame = now
        fallbackBase = if (usable == null) now else QlFrame.Identity
        sourceRect = usable
        dragY = 0f
        progress.snapTo(1f)
        backdrop.snapTo(backdropNow)
        chrome.snapTo(chromeNow)
        coroutineScope {
            launch { chrome.animateTo(0f, DangoMotion.fade()) }
            launch { backdrop.animateTo(0f, tween(DangoMotion.QUICK_LOOK_FADE_OUT_MS)) }
            progress.animateTo(0f, DangoMotion.quickLook())
        }
        closing = false
    }
}

val LocalQuickLookMotion = staticCompositionLocalOf<QuickLookMotion?> { null }

/**
 * Quick Look の表示枠（SPEC §5: 押したアイテムから拡大し、閉じると表示中ページの
 * アイテムへ縮小する。アイテムが画面に無ければフェード）。
 * [currentKey] は開いた・表示中のファイルの path.key
 */
@Composable
fun QuickLookOverlay(
    visible: Boolean,
    currentKey: String?,
    content: @Composable () -> Unit,
) {
    val motion = remember { QuickLookMotion() }
    val registry = LocalItemBounds.current
    motion.rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // 開いたその回のコンポジションで中身を合成する（効果を待つと 1 フレーム遅れる）
    if (visible && !motion.requested) {
        motion.requested = true
        motion.present = true
        motion.session++
        motion.openingKey = currentKey
        motion.waitingContent = true
    } else if (!visible && motion.requested) {
        motion.requested = false
    }
    val latestKey by rememberUpdatedState(currentKey)
    LaunchedEffect(visible) {
        if (visible) {
            motion.enter(latestKey?.let { registry?.get(it) })
        } else if (motion.present) {
            motion.exit(latestKey?.let { registry?.get(it) })
            motion.present = false
        }
    }
    if (!motion.present) return
    CompositionLocalProvider(
        LocalQuickLookMotion provides motion,
        // Quick Look は常にダーク表示（押下表現やメニューも暗い配色に揃える）
        LocalDangoColors provides DarkDangoColors,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { motion.overlayBounds = it.boundsInRoot() }
                .then(if (motion.closing) Modifier.consumeAllPointers() else Modifier),
        ) {
            key(motion.session) { content() }
        }
    }
}

/** 閉じるアニメーション中はページに触らせない */
private fun Modifier.consumeAllPointers(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/**
 * ページャに掛ける拡大・移動と不透明度。描画だけを変換し、ポインタ座標は変えない
 * （指に追従させる移動で中身のスクロール量が打ち消されないようにするため）
 */
internal fun Modifier.quickLookContent(motion: QuickLookMotion?): Modifier =
    if (motion == null) {
        this
    } else {
        this
            .graphicsLayer { alpha = motion.contentAlpha() }
            .drawWithContent {
                val f = motion.currentFrame()
                if (f == QlFrame.Identity) {
                    drawContent()
                } else {
                    withTransform({
                        translate(f.tx, f.ty)
                        scale(f.scale, f.scale, pivot = center)
                    }) {
                        this@drawWithContent.drawContent()
                    }
                }
            }
    }

/**
 * 下スワイプで閉じる（SPEC §5: 指に追従）。全ページ共通でページャの外側に掛ける。
 * 中身がスクロールできる間はスクロールを優先し、上端で余った下向きのスクロール
 * （ネストスクロール）か、スクロールしない中身での縦ドラッグで追従を始める
 */
internal class QuickLookDismissState(
    private val motion: QuickLookMotion,
    private val scope: CoroutineScope,
) {
    var enabled = true
    var onDismiss: () -> Unit = {}
    var flingVelocity = Float.MAX_VALUE
    private var settleJob: Job? = null
    private var nestedDragging = false

    private val canDrag: Boolean get() = enabled && !motion.closing && !motion.dismissing

    private fun begin() {
        settleJob?.cancel()
        settleJob = null
    }

    private fun dragBy(dy: Float): Float {
        val before = motion.dragY
        val after = (before + dy).coerceAtLeast(0f)
        motion.dragY = after
        return after - before
    }

    fun release(velocityY: Float) {
        nestedDragging = false
        val y = motion.dragY
        if (y <= 0f || motion.dismissing) return
        val height = motion.overlayBounds.height
        val dismiss = velocityY > flingVelocity ||
            (y > height * DISMISS_FRACTION && velocityY > -flingVelocity / 2f)
        Log.d("dango", "QL swipe release y=${y.toInt()} v=${velocityY.toInt()} dismiss=$dismiss")
        if (dismiss) {
            motion.dismissing = true
            onDismiss()
        } else {
            settleJob = scope.launch {
                animate(y, 0f, velocityY, DangoMotion.quickLook()) { v, _ -> motion.dragY = v }
            }
        }
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // 追従中に指を戻したら、中身をスクロールする前にこちらを戻す
            if (!nestedDragging || source != NestedScrollSource.UserInput || available.y >= 0f) {
                return Offset.Zero
            }
            return Offset(0f, dragBy(available.y))
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
            if (!nestedDragging) {
                if (!canDrag) return Offset.Zero
                nestedDragging = true
                begin()
            }
            return Offset(0f, dragBy(available.y))
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!nestedDragging) return Velocity.Zero
            release(available.y)
            return available
        }
    }

    suspend fun PointerInputScope.detectDrag() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val slop = viewConfiguration.touchSlop
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            var total = Offset.Zero
            var dragging = false
            var gaveUp = !canDrag
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) {
                    if (dragging) {
                        change?.let { tracker.addPointerInputChange(it) }
                        release(tracker.calculateVelocity().y)
                    } else if (nestedDragging) {
                        // 中身のリストから渡ったドラッグは通常フリングで判定される。来なければ戻す
                        scope.launch {
                            withFrameNanos { }
                            withFrameNanos { }
                            if (nestedDragging) release(0f)
                        }
                    }
                    break
                }
                if (dragging) {
                    dragBy(change.positionChange().y)
                    change.consume()
                    tracker.addPointerInputChange(change)
                    continue
                }
                if (gaveUp || change.isConsumed) continue
                if (event.changes.count { it.pressed } > 1) {
                    gaveUp = true
                    continue
                }
                total += change.positionChange()
                tracker.addPointerInputChange(change)
                when {
                    // 横のページ送りを奪わないよう、はっきり下向きのときだけ
                    total.y > slop && total.y > abs(total.x) * 1.2f -> {
                        dragging = true
                        begin()
                        dragBy(total.y - slop)
                        change.consume()
                    }
                    abs(total.x) > slop || total.y < -slop -> gaveUp = true
                }
            }
        }
    }
}

@Composable
internal fun rememberQuickLookDismiss(
    motion: QuickLookMotion?,
    enabled: Boolean,
    onDismiss: () -> Unit,
): QuickLookDismissState? {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val state = remember(motion) { motion?.let { QuickLookDismissState(it, scope) } } ?: return null
    state.enabled = enabled
    state.onDismiss = onDismiss
    state.flingVelocity = with(density) { DISMISS_FLING.toPx() }
    return state
}

internal fun Modifier.quickLookDismiss(state: QuickLookDismissState?): Modifier =
    if (state == null) {
        this
    } else {
        this
            .nestedScroll(state.connection)
            .pointerInput(state) { with(state) { detectDrag() } }
    }
