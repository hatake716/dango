package io.github.hatake716.dango.ui.browser.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.hatake716.dango.ui.theme.DangoMotion
import io.github.hatake716.dango.ui.theme.DangoTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * macOS（NSMenu）風のメニュー。Material の DropdownMenu は原点からズームして出るが、
 * macOS のメニューはほぼ即時に現れ、閉じるときだけ短くフェードする。
 * 行は青い角丸でハイライトし、選んだ項目は一瞬点滅してから実行する
 */

private val MenuShape = RoundedCornerShape(8.dp)
private val ItemShape = RoundedCornerShape(5.dp)
private val MENU_MIN_WIDTH = 200.dp
private val ITEM_HEIGHT = 34.dp
private val CHECK_GUTTER = 22.dp
private val SCREEN_MARGIN = 8.dp

/**
 * @param atPointer true: [offset] をアンカー左上からの位置とみなし、メニュー左上をそこに置く
 *   （右クリック位置に出す。はみ出すときは左/上へ反転）。
 *   false: アンカーの下に出す（ツールバーのボタンなど）
 */
@Composable
fun FinderMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    offset: DpOffset = DpOffset.Zero,
    atPointer: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = expanded
    if (!visibleState.currentState && !visibleState.targetState) return

    val density = LocalDensity.current
    val provider = remember(offset, atPointer, density) {
        FinderMenuPositionProvider(offset, atPointer, density)
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        val transition = rememberTransition(visibleState, label = "finderMenu")
        val alpha by transition.animateFloat(
            transitionSpec = {
                if (targetState) DangoMotion.menuIn() else DangoMotion.menuOut()
            },
            label = "menuAlpha",
        ) { if (it) 1f else 0f }
        val colors = DangoTheme.colors
        val dark = colors.windowBackground.luminance() < 0.5f
        val border = if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.12f)
        Column(
            modifier = Modifier
                .graphicsLayer { this.alpha = alpha }
                .padding(4.dp)
                .shadow(12.dp, MenuShape, clip = false)
                .clip(MenuShape)
                .background(colors.toolbar)
                .border(0.5.dp, border, MenuShape)
                .width(IntrinsicSize.Max)
                .widthIn(min = MENU_MIN_WIDTH)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 5.dp),
            content = content,
        )
    }
}

/**
 * メニュー項目。左端に ✓ 用の余白を常に取り（macOS と同じ揃え方）、
 * 押下・ホバー中は青い角丸ハイライト＋白文字。選ぶと短く点滅してから [onClick]
 */
@Composable
fun FinderMenuItem(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    checked: Boolean = false,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    destructive: Boolean = false,
) {
    val colors = DangoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var blink by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    val highlighted = enabled && (blink ?: (hovered || pressed))
    val baseText = when {
        !enabled -> colors.textSecondary.copy(alpha = 0.6f)
        destructive -> Color(0xFFFF3B30)
        else -> colors.textPrimary
    }
    val fg = if (highlighted) colors.onSelection else baseText
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ITEM_HEIGHT)
            .padding(horizontal = 5.dp)
            .clip(ItemShape)
            .background(if (highlighted) colors.selectionFocused else Color.Transparent)
            .hoverable(interaction, enabled)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && blink == null,
            ) {
                // macOS のように選択項目を一瞬点滅させてから実行する
                scope.launch {
                    blink = false
                    delay(45)
                    blink = true
                    delay(55)
                    blink = null
                    onClick()
                }
            }
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(CHECK_GUTTER), contentAlignment = Alignment.Center) {
            if (checked) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = if (highlighted) fg else colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            color = fg,
            fontSize = 14.sp,
            letterSpacing = (-0.2).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailingIcon != null) {
            Spacer(Modifier.width(12.dp))
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = if (highlighted) fg else colors.textSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 区切り線（左右に余白を取った細線） */
@Composable
fun FinderMenuDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .height(1.dp)
            .background(DangoTheme.colors.divider),
    )
}

/** 見出し（「テーマ」「タグ」など。操作できない小さい補助テキスト） */
@Composable
fun FinderMenuHeader(text: String) {
    Text(
        text = text,
        color = DangoTheme.colors.textSecondary,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier
            .heightIn(min = 22.dp)
            .padding(start = 5.dp + CHECK_GUTTER, end = 12.dp, top = 4.dp),
    )
}

private class FinderMenuPositionProvider(
    private val offset: DpOffset,
    private val atPointer: Boolean,
    private val density: Density,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = with(density) { SCREEN_MARGIN.roundToPx() }
        val ox = with(density) { offset.x.roundToPx() }
        val oy = with(density) { offset.y.roundToPx() }
        val w = popupContentSize.width
        val h = popupContentSize.height
        val maxX = (windowSize.width - w - margin).coerceAtLeast(margin)
        val maxY = (windowSize.height - h - margin).coerceAtLeast(margin)
        val x: Int
        val y: Int
        if (atPointer) {
            val px = anchorBounds.left + ox
            val py = anchorBounds.top + oy
            // 右/下にはみ出すならカーソルの左/上へ反転（macOS のコンテキストメニューと同じ）
            x = (if (px + w > windowSize.width - margin) px - w else px).coerceIn(margin, maxX)
            y = (if (py + h > windowSize.height - margin) py - h else py).coerceIn(margin, maxY)
        } else {
            val toRight = anchorBounds.left + ox
            val toLeft = anchorBounds.right - ox - w
            x = (if (toRight + w <= windowSize.width - margin) toRight else toLeft).coerceIn(margin, maxX)
            val below = anchorBounds.bottom + oy
            val above = anchorBounds.top - oy - h
            y = (if (below + h <= windowSize.height - margin) below else above).coerceIn(margin, maxY)
        }
        return IntOffset(x, y)
    }
}
