package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.BuildConfig
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.prefs.Settings
import io.github.hatake716.dango.domain.model.ThemeMode
import io.github.hatake716.dango.ui.theme.DangoTheme

private val SheetShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
private val GroupShape = RoundedCornerShape(10.dp)
private val TRASH_DAYS = listOf(7, 30, 90)

/** 設定画面（SPEC §10。M6）。macOS のシステム設定と同じく角丸のグループに行を並べる */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: Settings,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetSingleTap: (Boolean) -> Unit,
    onSetTrashDays: (Int) -> Unit,
    onClearCache: () -> Unit,
    onSetBiometric: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DangoTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = SheetShape,
        containerColor = colors.windowBackground,
        dragHandle = { FinderSheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                color = colors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )

            SectionLabel(stringResource(R.string.settings_appearance))
            SettingsGroup {
                val themes = listOf(
                    ThemeMode.SYSTEM to R.string.theme_system,
                    ThemeMode.LIGHT to R.string.theme_light,
                    ThemeMode.DARK to R.string.theme_dark,
                )
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(stringResource(R.string.menu_theme), color = colors.textPrimary, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    FinderSegmented(
                        options = themes.map { stringResource(it.second) },
                        selectedIndex = themes.indexOfFirst { it.first == settings.themeMode }.coerceAtLeast(0),
                        onSelect = { onSetThemeMode(themes[it].first) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                GroupDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_dynamic_color),
                    description = stringResource(R.string.settings_dynamic_color_desc),
                    checked = settings.dynamicColor,
                    onChange = onSetDynamicColor,
                )
            }

            SectionLabel(stringResource(R.string.settings_behavior))
            SettingsGroup {
                SwitchRow(
                    title = stringResource(R.string.settings_single_tap),
                    description = stringResource(R.string.settings_single_tap_desc),
                    checked = settings.singleTapOpen,
                    onChange = onSetSingleTap,
                )
            }

            SectionLabel(stringResource(R.string.settings_storage))
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_trash_days),
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    FinderSegmented(
                        options = TRASH_DAYS.map { stringResource(R.string.settings_trash_days_value, it) },
                        selectedIndex = TRASH_DAYS.indexOf(settings.trashAutoDays),
                        onSelect = { onSetTrashDays(TRASH_DAYS[it]) },
                    )
                }
                GroupDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_clear_cache),
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                        )
                        Text(
                            stringResource(R.string.settings_clear_cache_desc),
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    FinderPushButton(
                        text = stringResource(R.string.settings_clear_cache_action),
                        onClick = onClearCache,
                        compact = true,
                        touchPadding = 8.dp,
                    )
                }
            }

            SectionLabel(stringResource(R.string.settings_security))
            SettingsGroup {
                SwitchRow(
                    title = stringResource(R.string.settings_biometric),
                    description = stringResource(R.string.settings_biometric_desc),
                    checked = settings.biometricLock,
                    onChange = onSetBiometric,
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.settings_version) + ": " + BuildConfig.VERSION_NAME,
                color = colors.textSecondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(18.dp))
    Text(
        text = text,
        color = DangoTheme.colors.textSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
    )
}

/** 角丸のグループ枠（macOS のシステム設定の inset グループ） */
@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    val colors = DangoTheme.colors
    val dark = colors.windowBackground.luminance() < 0.5f
    val border = if (dark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GroupShape)
            .background(colors.sidebar)
            .border(0.5.dp, border, GroupShape),
        content = content,
    )
}

/** グループ内の行区切り（左右 12dp 内側に寄せた細線） */
@Composable
private fun GroupDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = DangoTheme.colors.divider,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val colors = DangoTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontSize = 13.sp)
            Text(description, color = colors.textSecondary, fontSize = 11.sp)
        }
        FinderSwitch(checked = checked, onCheckedChange = onChange)
    }
}
