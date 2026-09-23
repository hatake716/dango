package io.github.hatake716.dango.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/**
 * Finder 風の押下表現（Material のリップルの代わり）。macOS のボタンは波紋を出さず、
 * 押している間だけ即座にわずかに暗く（ダークでは明るく）なり、離すと短く戻る。
 * マウスのホバーでは押下の半分の濃さで反応する（ツールバーのボタンと同じ）。
 * クリップは呼び出し側の clip に従う
 */
object FinderPressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        FinderPressNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = System.identityHashCode(this)
}

private const val PRESSED_ALPHA = 0.10f
private const val HOVER_LEVEL = 0.5f

private class FinderPressNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {

    private val level = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            var pressed = 0
            var hovered = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed++
                    is PressInteraction.Release -> pressed = (pressed - 1).coerceAtLeast(0)
                    is PressInteraction.Cancel -> pressed = (pressed - 1).coerceAtLeast(0)
                    is HoverInteraction.Enter -> hovered++
                    is HoverInteraction.Exit -> hovered = (hovered - 1).coerceAtLeast(0)
                }
                val target = when {
                    pressed > 0 -> 1f
                    hovered > 0 -> HOVER_LEVEL
                    else -> 0f
                }
                launch {
                    // 押し込みは即時、戻りだけ短くフェード（macOS のボタンの感触）
                    if (target > level.value) {
                        level.snapTo(target)
                    } else {
                        level.animateTo(target, DangoMotion.pressOut())
                    }
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val v = level.value
        if (v > 0f) {
            val tint = currentValueOf(LocalDangoColors).textPrimary
            drawRect(tint.copy(alpha = PRESSED_ALPHA * v))
        }
    }
}
