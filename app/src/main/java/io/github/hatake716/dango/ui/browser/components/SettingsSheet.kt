package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.BuildConfig
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.prefs.Settings
import io.github.hatake716.dango.domain.model.ThemeMode
import io.github.hatake716.dango.ui.theme.DangoTheme

/** 設定画面（SPEC §10。M6） */
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
        containerColor = colors.windowBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                color = colors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            SectionLabel(stringResource(R.string.settings_appearance))
            Row {
                listOf(
                    ThemeMode.SYSTEM to R.string.theme_system,
                    ThemeMode.LIGHT to R.string.theme_light,
                    ThemeMode.DARK to R.string.theme_dark,
                ).forEach { (mode, labelRes) ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { onSetThemeMode(mode) },
                        label = { Text(stringResource(labelRes), fontSize = 12.sp) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            SwitchRow(
                title = stringResource(R.string.settings_dynamic_color),
                description = stringResource(R.string.settings_dynamic_color_desc),
                checked = settings.dynamicColor,
                onChange = onSetDynamicColor,
            )

            SectionLabel(stringResource(R.string.settings_behavior))
            SwitchRow(
                title = stringResource(R.string.settings_single_tap),
                description = stringResource(R.string.settings_single_tap_desc),
                checked = settings.singleTapOpen,
                onChange = onSetSingleTap,
            )

            SectionLabel(stringResource(R.string.settings_storage))
            Text(
                text = stringResource(R.string.settings_trash_days),
                color = colors.textPrimary,
                fontSize = 14.sp,
            )
            Row {
                listOf(7, 30, 90).forEach { days ->
                    FilterChip(
                        selected = settings.trashAutoDays == days,
                        onClick = { onSetTrashDays(days) },
                        label = {
                            Text(stringResource(R.string.settings_trash_days_value, days), fontSize = 12.sp)
                        },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_clear_cache),
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                    )
                    Text(
                        stringResource(R.string.settings_clear_cache_desc),
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                    )
                }
                TextButton(onClick = onClearCache) {
                    Text(stringResource(R.string.settings_clear_cache))
                }
            }

            SectionLabel(stringResource(R.string.settings_security))
            SwitchRow(
                title = stringResource(R.string.settings_biometric),
                description = stringResource(R.string.settings_biometric_desc),
                checked = settings.biometricLock,
                onChange = onSetBiometric,
            )

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = colors.divider)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_version) + ": " + BuildConfig.VERSION_NAME,
                color = colors.textSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val colors = DangoTheme.colors
    Spacer(Modifier.height(16.dp))
    Text(
        text = text,
        color = colors.textSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
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
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontSize = 14.sp)
            Text(description, color = colors.textSecondary, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
