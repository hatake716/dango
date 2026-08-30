package io.github.hatake716.dango.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.domain.model.ThemeMode

/** Finder 配色トークン（SPEC §9）。Material You 動的カラーは既定オフ */
@Immutable
data class DangoColors(
    val windowBackground: Color,
    val sidebar: Color,
    val toolbar: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val selectionFocused: Color,
    val selectionUnfocused: Color,
    val altRow: Color,
    val accent: Color,
    val onSelection: Color,
)

val LightDangoColors = DangoColors(
    windowBackground = Color(0xFFFFFFFF),
    sidebar = Color(0xFFF5F5F7),
    toolbar = Color(0xFFF6F6F6),
    divider = Color(0xFFE5E5E5),
    textPrimary = Color(0xFF1D1D1F),
    textSecondary = Color(0xFF86868B),
    selectionFocused = Color(0xFF0A60FF),
    selectionUnfocused = Color(0xFFDCDCDC),
    altRow = Color(0xFFF7F7F7),
    accent = Color(0xFF5AA1F2),
    onSelection = Color(0xFFFFFFFF),
)

val DarkDangoColors = DangoColors(
    windowBackground = Color(0xFF1E1E1E),
    sidebar = Color(0xFF2B2B2B),
    toolbar = Color(0xFF323232),
    divider = Color(0xFF3A3A3A),
    textPrimary = Color(0xFFE8E8E8),
    textSecondary = Color(0xFF9A9A9A),
    selectionFocused = Color(0xFF2F6FED),
    selectionUnfocused = Color(0xFF454545),
    altRow = Color(0xFF262626),
    accent = Color(0xFF5AA1F2),
    onSelection = Color(0xFFFFFFFF),
)

val LocalDangoColors = staticCompositionLocalOf { LightDangoColors }

object DangoTheme {
    val colors: DangoColors
        @Composable get() = LocalDangoColors.current
}

@Composable
fun isDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** 英数を SF 風に見せる字間 -0.2sp（SPEC §9） */
private val DangoTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(letterSpacing = (-0.2).sp),
        titleMedium = t.titleMedium.copy(letterSpacing = (-0.2).sp),
        titleSmall = t.titleSmall.copy(letterSpacing = (-0.2).sp),
        bodyLarge = t.bodyLarge.copy(letterSpacing = (-0.2).sp),
        bodyMedium = t.bodyMedium.copy(letterSpacing = (-0.2).sp),
        bodySmall = t.bodySmall.copy(letterSpacing = (-0.2).sp),
        labelLarge = t.labelLarge.copy(letterSpacing = (-0.2).sp),
        labelMedium = t.labelMedium.copy(letterSpacing = (-0.2).sp),
        labelSmall = t.labelSmall.copy(letterSpacing = (-0.2).sp),
    )
}

/**
 * ライト/ダーク切替はトークンごとの色アニメーションで全面クロスフェードに見せる（SPEC §5: 300ms）。
 * ツリーを作り直す Crossfade はスクロール位置や Snackbar などの remember 状態を失うため使わない。
 */
@Composable
private fun animatedDangoColors(target: DangoColors): DangoColors {
    val spec = tween<Color>(durationMillis = 300)
    val windowBackground by animateColorAsState(target.windowBackground, spec, label = "windowBg")
    val sidebar by animateColorAsState(target.sidebar, spec, label = "sidebar")
    val toolbar by animateColorAsState(target.toolbar, spec, label = "toolbar")
    val divider by animateColorAsState(target.divider, spec, label = "divider")
    val textPrimary by animateColorAsState(target.textPrimary, spec, label = "textPrimary")
    val textSecondary by animateColorAsState(target.textSecondary, spec, label = "textSecondary")
    val selectionFocused by animateColorAsState(target.selectionFocused, spec, label = "selFocused")
    val selectionUnfocused by animateColorAsState(target.selectionUnfocused, spec, label = "selUnfocused")
    val altRow by animateColorAsState(target.altRow, spec, label = "altRow")
    val accent by animateColorAsState(target.accent, spec, label = "accent")
    val onSelection by animateColorAsState(target.onSelection, spec, label = "onSelection")
    return DangoColors(
        windowBackground = windowBackground,
        sidebar = sidebar,
        toolbar = toolbar,
        divider = divider,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        selectionFocused = selectionFocused,
        selectionUnfocused = selectionUnfocused,
        altRow = altRow,
        accent = accent,
        onSelection = onSelection,
    )
}

@Composable
fun DangoTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme(themeMode)
    // Material You 動的カラー（SPEC §9: 既定オフ。有効時はアクセントのみ端末カラーに追従）
    val base = if (dark) DarkDangoColors else LightDangoColors
    val target = if (dynamicColor && android.os.Build.VERSION.SDK_INT >= 31) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val dynamic = if (dark) {
            androidx.compose.material3.dynamicDarkColorScheme(context)
        } else {
            androidx.compose.material3.dynamicLightColorScheme(context)
        }
        base.copy(accent = dynamic.primary, selectionFocused = dynamic.primary)
    } else {
        base
    }
    val colors = animatedDangoColors(target)
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.selectionFocused,
            onPrimary = colors.onSelection,
            background = colors.windowBackground,
            onBackground = colors.textPrimary,
            surface = colors.toolbar,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.sidebar,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.divider,
            outlineVariant = colors.divider,
            secondaryContainer = colors.selectionUnfocused,
            onSecondaryContainer = colors.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = colors.selectionFocused,
            onPrimary = colors.onSelection,
            background = colors.windowBackground,
            onBackground = colors.textPrimary,
            surface = colors.toolbar,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.sidebar,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.divider,
            outlineVariant = colors.divider,
            secondaryContainer = colors.selectionUnfocused,
            onSecondaryContainer = colors.textPrimary,
        )
    }
    CompositionLocalProvider(LocalDangoColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = DangoTypography, content = content)
    }
}
