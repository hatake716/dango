package io.github.hatake716.dango.ui.browser.components

import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.hatake716.dango.R
import io.github.hatake716.dango.ui.theme.DangoColors
import io.github.hatake716.dango.ui.theme.DangoMotion
import io.github.hatake716.dango.ui.theme.DangoTheme
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * macOS（AppKit）風の小さなコントロール群: アラート、プッシュボタン、テキストフィールド、
 * セグメンテッドコントロール、ポップアップボタン、チェックボックス／スイッチ
 */

enum class FinderButtonStyle { Default, Normal, Destructive }

/**
 * アラートのボタン。[dismisses] が true のものは閉じるアニメーションの後に [onClick] を呼ぶ。
 * 押してもダイアログを閉じない操作（接続テストなど）は false にする
 */
@Immutable
class FinderAlertButton(
    val text: String,
    val style: FinderButtonStyle = FinderButtonStyle.Normal,
    val enabled: Boolean = true,
    val dismisses: Boolean = true,
    val onClick: () -> Unit,
)

private val FinderRed = Color(0xFFFF3B30)
private val AlertShape = RoundedCornerShape(12.dp)
private val PushShape = RoundedCornerShape(7.dp)
private val CompactPushShape = RoundedCornerShape(6.dp)
private val FieldShape = RoundedCornerShape(6.dp)
private val SegmentTrackShape = RoundedCornerShape(7.dp)
private val SegmentShape = RoundedCornerShape(6.dp)
private const val SCRIM_ALPHA = 0.2f

/** アクションの後も呼び出し側がダイアログを外さなかったとき、出し直すまでの待ち時間 */
private const val REOPEN_AFTER_MS = 400L
private const val SHOW_FALLBACK_MS = 400L

private val DangoColors.isDark: Boolean get() = windowBackground.luminance() < 0.5f

/** NSColor.controlBackgroundColor 相当（ボタン・入力欄・セグメントのつまみ） */
private val DangoColors.controlBackground: Color
    get() = if (isDark) selectionUnfocused else windowBackground

/** パネルやコントロールの縁取り（macOS の 0.5pt の細い境界線） */
private val DangoColors.hairline: Color
    get() = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.12f)

/**
 * macOS（Big Sur 以降）のアラート。中央の小さなパネルにアイコン・太字タイトル・本文を中央揃えで置き、
 * 横幅いっぱいのプッシュボタンを並べる（3 つ以上か長いラベルは縦積み）。
 *
 * [buttons] は NSAlert と同じ順（先頭が既定ボタン）。横並びでは先頭が右端、縦積みでは先頭が最上段。
 * 出るときは 1.04→1.0 に縮みながらフェードイン、閉じるときはフェードアウトしてから
 * コールバックを呼ぶ（呼び出し側がダイアログを外すまで表示を保つため）。
 * 戻る操作・パネル外タップで閉じる（M3 AlertDialog と同じ）
 */
@Composable
fun FinderAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    buttons: List<FinderAlertButton>,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: (@Composable () -> Unit)? = null,
    width: Dp = 288.dp,
    stackButtons: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val visibleState = remember { MutableTransitionState(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val closing = pendingAction != null

    fun close(action: () -> Unit) {
        if (pendingAction != null) return
        pendingAction = action
        visibleState.targetState = false
    }

    if (closing && visibleState.isIdle && !visibleState.currentState) {
        LaunchedEffect(Unit) {
            pendingAction?.invoke()
            delay(REOPEN_AFTER_MS)
            pendingAction = null
            visibleState.targetState = true
        }
    }

    Dialog(
        onDismissRequest = { close(onDismissRequest) },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        OwnScrimWindow()
        // ダイアログのウインドウが画面に出て（フォーカスを得て）から出現アニメーションを始める。
        // ウインドウ生成に時間がかかる端末で、アニメーションの前半が見えないまま終わるのを防ぐ
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val observer = dialogView.viewTreeObserver
            val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                if (hasFocus && pendingAction == null) visibleState.targetState = true
            }
            if (dialogView.hasWindowFocus()) visibleState.targetState = true
            observer.addOnWindowFocusChangeListener(listener)
            onDispose {
                if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
            }
        }
        LaunchedEffect(Unit) {
            // フォーカスが来ない場合の保険
            delay(SHOW_FALLBACK_MS)
            if (pendingAction == null) visibleState.targetState = true
        }
        val colors = DangoTheme.colors
        val transition = rememberTransition(visibleState, label = "finderAlert")
        val alpha by transition.animateFloat(
            transitionSpec = {
                if (targetState) DangoMotion.alertIn() else DangoMotion.alertOut()
            },
            label = "alertAlpha",
        ) { if (it) 1f else 0f }
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind { drawRect(Color.Black.copy(alpha = SCRIM_ALPHA * alpha)) }
                    .pointerInput(Unit) { detectTapGestures { close(onDismissRequest) } },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = modifier
                        .graphicsLayer {
                            this.alpha = alpha
                            // 縮小は出るときだけ（閉じるときは大きさを変えずに消える）
                            val s = if (visibleState.targetState) {
                                DangoMotion.ALERT_SCALE + (1f - DangoMotion.ALERT_SCALE) * alpha
                            } else {
                                1f
                            }
                            scaleX = s
                            scaleY = s
                        }
                        .width(width)
                        .shadow(
                            elevation = 18.dp,
                            shape = AlertShape,
                            ambientColor = Color.Black.copy(alpha = 0.3f),
                            spotColor = Color.Black.copy(alpha = 0.35f),
                        )
                        .clip(AlertShape)
                        .background(colors.toolbar)
                        .border(0.5.dp, colors.hairline, AlertShape)
                        // パネル上のタップを背面の「外側タップで閉じる」に渡さない
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
                ) {
                    AlertBody(
                        icon = icon,
                        title = title,
                        message = message,
                        content = content,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.height(12.dp))
                    AlertButtons(
                        buttons = buttons,
                        forceStack = stackButtons,
                        clickable = !closing,
                        onClick = { button ->
                            if (button.dismisses) close(button.onClick) else button.onClick()
                        },
                    )
                }
            }
        }
    }
}

/** アラートのアイコン・タイトル・本文・任意の中身。長いときはこの部分だけスクロールする */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlertBody(
    icon: (@Composable () -> Unit)?,
    title: String,
    message: String?,
    content: (@Composable ColumnScope.() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = DangoTheme.colors
    val density = LocalDensity.current
    val bringIntoView = remember(density) {
        PaddedBringIntoViewSpec(with(density) { FOCUS_RING_ROOM.toPx() })
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
        Column(
            modifier = modifier
                .verticalScroll(rememberScrollState())
                // フォーカスリングが縦スクロールの切り抜きで欠けないための余白
                .padding(bottom = FOCUS_RING_ROOM),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (icon != null) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    icon()
                }
                Spacer(Modifier.height(10.dp))
            }
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (message != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = message,
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
            if (content != null) {
                Spacer(Modifier.height(12.dp))
                Column(modifier = Modifier.fillMaxWidth(), content = content)
            }
        }
    }
}

private val FOCUS_RING_ROOM = 4.dp

/**
 * 入力欄へのスクロール（IME 表示時など）で、枠の外に描くフォーカスリングの分まで見せる
 */
@OptIn(ExperimentalFoundationApi::class)
private class PaddedBringIntoViewSpec(private val padding: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val leading = offset - padding
        val trailing = offset + size + padding
        return when {
            leading >= 0f && trailing <= containerSize -> 0f
            leading < 0f && trailing > containerSize -> 0f
            abs(leading) < abs(trailing - containerSize) -> leading
            else -> trailing - containerSize
        }
    }
}

/**
 * ダイアログのウインドウ標準の暗幕と出入りアニメーションを止める。
 * 暗幕は自前で描いてフェードさせ、出入りもアラート側のアニメーションだけにするため
 */
@Composable
private fun OwnScrimWindow() {
    val view = LocalView.current
    SideEffect {
        val window = (view as? DialogWindowProvider)?.window
            ?: (view.parent as? DialogWindowProvider)?.window
            ?: return@SideEffect
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        window.setWindowAnimations(android.R.style.Animation)
    }
}

/**
 * ボタン列。2 つまでで各ラベルが半分の幅に収まるなら横並び（既定ボタンが右）、
 * それ以外は縦積み（既定ボタンが上）
 */
@Composable
private fun AlertButtons(
    buttons: List<FinderAlertButton>,
    forceStack: Boolean,
    clickable: Boolean,
    onClick: (FinderAlertButton) -> Unit,
) {
    Layout(
        content = {
            buttons.forEach { button ->
                FinderPushButton(
                    text = button.text,
                    onClick = { if (clickable) onClick(button) },
                    style = button.style,
                    enabled = button.enabled,
                )
            }
        },
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val width = constraints.maxWidth
        val half = (width - gap) / 2
        val stacked = forceStack || measurables.size > 2 ||
            measurables.any { it.maxIntrinsicWidth(Constraints.Infinity) > half }
        if (stacked || measurables.size <= 1) {
            val placeables = measurables.map {
                it.measure(Constraints.fixedWidth(width))
            }
            layout(width, placeables.sumOf { it.height }) {
                var y = 0
                placeables.forEach {
                    it.place(0, y)
                    y += it.height
                }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(half)) }
            val height = placeables.maxOf { it.height }
            layout(width, height) {
                // NSAlert と同じく先頭（既定）が右端
                placeables.reversed().forEachIndexed { i, p -> p.place(i * (half + gap), 0) }
            }
        }
    }
}

/**
 * macOS のプッシュボタン。見た目は 32dp（[compact] は 24dp）の角丸で、
 * タップ判定は上下に [touchPadding] ずつ広げる（その分レイアウトも高くなる）。
 * 既定ボタンはアクセント色の塗り＋白文字、破壊的操作は赤文字
 */
@Composable
fun FinderPushButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: FinderButtonStyle = FinderButtonStyle.Normal,
    enabled: Boolean = true,
    compact: Boolean = false,
    touchPadding: Dp = 4.dp,
) {
    val colors = DangoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = if (compact) CompactPushShape else PushShape
    val filled = style == FinderButtonStyle.Default && enabled
    val background = if (filled) colors.selectionFocused else colors.controlBackground
    val foreground = when {
        !enabled -> colors.textSecondary.copy(alpha = 0.6f)
        filled -> colors.onSelection
        style == FinderButtonStyle.Destructive -> FinderRed
        else -> colors.textPrimary
    }
    Box(
        modifier = modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = touchPadding),
        contentAlignment = Alignment.Center,
        // 幅が固定で渡されたとき（アラートのボタン列）は見た目もその幅いっぱいにする
        propagateMinConstraints = true,
    ) {
        Box(
            modifier = Modifier
                .height(if (compact) 24.dp else 32.dp)
                .shadow(if (filled || colors.isDark) 0.dp else 0.5.dp, shape)
                .clip(shape)
                .background(background)
                .then(if (filled) Modifier else Modifier.border(0.5.dp, colors.hairline, shape))
                .indication(interaction, LocalIndication.current)
                .padding(horizontal = if (compact) 10.dp else 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = foreground,
                fontSize = if (compact) 12.sp else 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * macOS のテキストフィールド。32dp の角丸に細い枠、フォーカス中は外側に青いフォーカスリング。
 * ラベルは入力欄の上に置く静的な小さい文字（M3 の浮き上がるラベルは使わない）
 */
@Composable
fun FinderTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    focusRequester: FocusRequester? = null,
) {
    val colors = DangoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = DangoMotion.fade(),
        label = "focusRing",
    )
    val ringColor = colors.selectionFocused.copy(alpha = if (colors.isDark) 0.85f else 0.6f)
    Column(modifier = modifier) {
        if (label != null) FinderFieldLabel(label)
        CompositionLocalProvider(
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = colors.selectionFocused,
                backgroundColor = colors.selectionFocused.copy(alpha = 0.3f),
            ),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(
                    TextStyle(
                        color = if (enabled) colors.textPrimary else colors.textSecondary,
                        fontSize = 13.sp,
                    ),
                ),
                cursorBrush = SolidColor(colors.selectionFocused),
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                interactionSource = interaction,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .drawWithContent {
                        drawContent()
                        if (ring > 0f) {
                            // 枠の外側 2dp に描く（macOS のフォーカスリングは枠の外へ広がる）
                            val w = 2.dp.toPx()
                            drawRoundRect(
                                color = ringColor.copy(alpha = ringColor.alpha * ring),
                                topLeft = Offset(-w / 2, -w / 2),
                                size = Size(size.width + w, size.height + w),
                                cornerRadius = CornerRadius(6.dp.toPx() + w / 2),
                                style = Stroke(width = w),
                            )
                        }
                    }
                    .clip(FieldShape)
                    .background(colors.controlBackground)
                    .border(0.5.dp, colors.hairline, FieldShape),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                color = colors.textSecondary.copy(alpha = 0.7f),
                                fontSize = 13.sp,
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}

/** 入力欄・セグメントの上に置く小さな見出し */
@Composable
fun FinderFieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = DangoTheme.colors.textSecondary,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = 2.dp, bottom = 4.dp),
    )
}

/**
 * macOS のセグメンテッドコントロール。薄いグレーのトラック上を白いつまみがスライドする。
 * 幅が決まっていれば等分、決まっていなければ最長ラベルに合わせた等幅
 */
@Composable
fun FinderSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DangoTheme.colors
    val dark = colors.isDark
    val track = colors.textPrimary.copy(alpha = if (dark) 0.10f else 0.06f)
    // ダークの #454545 はトラックとほぼ同じ明るさで選択が見えないため、明るい半透明にする
    val thumb = if (dark) colors.textPrimary.copy(alpha = 0.24f) else colors.windowBackground
    val separator = colors.textPrimary.copy(alpha = 0.14f)
    val thumbIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = DangoMotion.segment(),
        label = "segmentedThumb",
    )
    Layout(
        modifier = modifier
            .height(28.dp)
            .clip(SegmentTrackShape)
            .background(track)
            .drawBehind {
                // つまみに接していない境目にだけ細い区切り線（macOS と同じ）
                val pad = 2.dp.toPx()
                val segW = (size.width - pad * 2) / options.size
                val lineH = 14.dp.toPx()
                for (b in 1 until options.size) {
                    val a = (abs(b - (thumbIndex + 0.5f)) - 0.5f).coerceIn(0f, 1f)
                    if (a <= 0f) continue
                    val x = pad + segW * b
                    drawRect(
                        color = separator.copy(alpha = separator.alpha * a),
                        topLeft = Offset(x - 0.5.dp.toPx(), (size.height - lineH) / 2),
                        size = Size(1.dp.toPx(), lineH),
                    )
                }
            },
        content = {
            Box(
                modifier = Modifier
                    .shadow(if (dark) 0.dp else 1.dp, SegmentShape)
                    .clip(SegmentShape)
                    .background(thumb),
            )
            options.forEachIndexed { i, label ->
                val selected = i == selectedIndex
                Box(
                    modifier = Modifier
                        .clip(SegmentShape)
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onSelect(i) },
                        )
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = colors.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val pad = 2.dp.roundToPx()
        val height = constraints.maxHeight
        val inner = (height - pad * 2).coerceAtLeast(0)
        val segments = measurables.drop(1)
        val n = segments.size.coerceAtLeast(1)
        val fixedWidth = constraints.hasBoundedWidth && constraints.minWidth == constraints.maxWidth
        val totalWidth = if (fixedWidth) {
            constraints.maxWidth
        } else {
            val widest = segments.maxOfOrNull { it.maxIntrinsicWidth(inner) } ?: 0
            (widest * n + pad * 2).coerceAtMost(constraints.maxWidth)
        }
        val segW = (totalWidth - pad * 2).toFloat() / n
        fun edge(i: Int) = pad + (segW * i).roundToInt()
        val placeables = segments.mapIndexed { i, m ->
            m.measure(Constraints.fixed(edge(i + 1) - edge(i), inner))
        }
        val thumbPlaceable = measurables[0].measure(Constraints.fixed(segW.roundToInt(), inner))
        layout(totalWidth, height) {
            thumbPlaceable.place(pad + (segW * thumbIndex).roundToInt(), pad)
            placeables.forEachIndexed { i, p -> p.place(edge(i), pad) }
        }
    }
}

/**
 * macOS のポップアップボタン（選択肢が多くセグメントに収まらないとき用）。
 * 右端の青い小箱に上下の山形を置き、押すと FinderMenu で選択肢を出す
 */
@Composable
fun FinderPopUpButton(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DangoTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .heightIn(min = 28.dp)
                .shadow(if (colors.isDark) 0.dp else 0.5.dp, CompactPushShape)
                .clip(CompactPushShape)
                .background(colors.controlBackground)
                .border(0.5.dp, colors.hairline, CompactPushShape)
                .clickable(role = Role.DropdownList) { expanded = true }
                .padding(start = 10.dp, end = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // macOS と同じく幅は最長の選択肢に合わせる（選び直しても幅が変わらない）
            Box(modifier = Modifier.weight(1f, fill = false)) {
                options.forEachIndexed { i, option ->
                    val shown = i == selectedIndex
                    Text(
                        text = option,
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (shown) {
                            Modifier
                        } else {
                            Modifier
                                .alpha(0f)
                                .clearAndSetSemantics {}
                        },
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(width = 18.dp, height = 22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.selectionFocused),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.UnfoldMore,
                    contentDescription = null,
                    tint = colors.onSelection,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        FinderMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, option ->
                FinderMenuItem(
                    text = option,
                    checked = i == selectedIndex,
                    onClick = {
                        expanded = false
                        onSelect(i)
                    },
                )
            }
        }
    }
}

/** macOS のチェックボックス（青い塗り・小さめ）。ラベルを含む行全体で切り替わる */
@Composable
fun FinderCheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = DangoTheme.colors
    Row(
        modifier = modifier
            .heightIn(min = 36.dp)
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = finderCheckboxColors(),
            modifier = Modifier.scale(0.85f),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = text, color = colors.textPrimary, fontSize = 13.sp)
    }
}

@Composable
fun finderCheckboxColors(): CheckboxColors {
    val colors = DangoTheme.colors
    return CheckboxDefaults.colors(
        checkedColor = colors.selectionFocused,
        uncheckedColor = colors.textSecondary,
        checkmarkColor = colors.onSelection,
    )
}

/**
 * macOS のスイッチ。つまみは常に同じ大きさ（M3 のようにオフで縮まない）で、
 * オンは青、オフは淡いトラック。全体を少し小さく描く
 */
@Composable
fun FinderSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = finderSwitchColors(),
        thumbContent = {},
        modifier = modifier.scale(0.8f),
    )
}

@Composable
fun finderSwitchColors(): SwitchColors {
    val colors = DangoTheme.colors
    val dark = colors.isDark
    return SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = colors.selectionFocused,
        checkedBorderColor = Color.Transparent,
        checkedIconColor = Color.Transparent,
        // ダークでは背景色のつまみが暗いトラックに埋もれるため、macOS 同様に明るいつまみにする
        uncheckedThumbColor = if (dark) Color(0xFFCACACA) else colors.windowBackground,
        uncheckedTrackColor = if (dark) colors.selectionUnfocused else colors.textPrimary.copy(alpha = 0.12f),
        uncheckedBorderColor = Color.Transparent,
        uncheckedIconColor = Color.Transparent,
    )
}

/** シート上端の小さな取っ手（36×4dp） */
@Composable
fun FinderSheetHandle() {
    val colors = DangoTheme.colors
    Box(
        modifier = Modifier
            .padding(top = 8.dp, bottom = 6.dp)
            .size(width = 36.dp, height = 4.dp)
            .clip(CircleShape)
            .background(if (colors.isDark) colors.selectionUnfocused else colors.divider),
    )
}

/** アラート用のアプリアイコン（macOS のアラートと同じく既定はアプリのアイコンを出す） */
@Composable
fun FinderAppIcon(size: Dp = 48.dp) {
    val shape = RoundedCornerShape(size * 0.225f)
    Box(
        modifier = Modifier
            .size(size)
            .shadow(1.dp, shape)
            .clip(shape)
            .background(colorResource(R.color.ic_launcher_background)),
        contentAlignment = Alignment.Center,
    ) {
        // アダプティブアイコンの前景は 108dp 中の中央 72dp が見える範囲
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(size * 1.5f),
        )
    }
}
