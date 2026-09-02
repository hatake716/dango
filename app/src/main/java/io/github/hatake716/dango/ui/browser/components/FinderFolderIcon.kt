package io.github.hatake716.dango.ui.browser.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * macOS Finder 風のフォルダアイコン（SPEC §15「独自青フォルダ」）。
 * 背面パネル（左上のタブが前面の上に覗く）＋前面パネルの2枚構成で、
 * どちらも縦グラデーション。Icon には tint = Color.Unspecified で渡し、
 * ベクター自身の色をそのまま描く（ライト/ダーク共通。Finder も同色）
 */
val FinderFolder: ImageVector by lazy {
    ImageVector.Builder(
        name = "FinderFolder",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 背面パネル（タブ付き）。前面より少し濃い青
        path(
            fill = Brush.linearGradient(
                colorStops = arrayOf(
                    0f to Color(0xFF4796E9),
                    1f to Color(0xFF3477D3),
                ),
                start = Offset(0f, 4.7f),
                end = Offset(0f, 19.5f),
            ),
        ) {
            moveTo(3.9f, 4.7f)
            horizontalLineTo(8.7f)
            curveTo(9.3f, 4.7f, 9.85f, 4.92f, 10.25f, 5.3f)
            lineTo(11.45f, 6.42f)
            curveTo(11.85f, 6.78f, 12.35f, 6.95f, 12.9f, 6.95f)
            horizontalLineTo(20.1f)
            quadTo(22f, 6.95f, 22f, 8.85f)
            verticalLineTo(17.6f)
            quadTo(22f, 19.5f, 20.1f, 19.5f)
            horizontalLineTo(3.9f)
            quadTo(2f, 19.5f, 2f, 17.6f)
            verticalLineTo(6.6f)
            quadTo(2f, 4.7f, 3.9f, 4.7f)
            close()
        }
        // 前面パネル。上が明るい縦グラデーション
        path(
            fill = Brush.linearGradient(
                colorStops = arrayOf(
                    0f to Color(0xFF66B5F7),
                    1f to Color(0xFF3B84E5),
                ),
                start = Offset(0f, 7.4f),
                end = Offset(0f, 19.5f),
            ),
        ) {
            moveTo(3.2f, 7.4f)
            horizontalLineTo(20.8f)
            quadTo(22f, 7.4f, 22f, 8.6f)
            verticalLineTo(17.6f)
            quadTo(22f, 19.5f, 20.1f, 19.5f)
            horizontalLineTo(3.9f)
            quadTo(2f, 19.5f, 2f, 17.6f)
            verticalLineTo(8.6f)
            quadTo(2f, 7.4f, 3.2f, 7.4f)
            close()
        }
        // 前面上端の淡いハイライト（立体感）
        path(
            fill = Brush.linearGradient(
                colorStops = arrayOf(
                    0f to Color(0x8CD4EDFF),
                    1f to Color(0x00D4EDFF),
                ),
                start = Offset(0f, 7.4f),
                end = Offset(0f, 9.2f),
            ),
        ) {
            moveTo(3.2f, 7.4f)
            horizontalLineTo(20.8f)
            quadTo(22f, 7.4f, 22f, 8.6f)
            verticalLineTo(9.2f)
            horizontalLineTo(2f)
            verticalLineTo(8.6f)
            quadTo(2f, 7.4f, 3.2f, 7.4f)
            close()
        }
    }.build()
}
