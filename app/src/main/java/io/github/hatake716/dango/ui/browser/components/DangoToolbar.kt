package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.domain.model.SortKey
import io.github.hatake716.dango.domain.model.SortSpec
import io.github.hatake716.dango.domain.model.ThemeMode
import io.github.hatake716.dango.domain.model.ViewMode
import io.github.hatake716.dango.ui.theme.DangoTheme

@Composable
fun DangoToolbar(
    title: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    viewMode: ViewMode,
    sort: SortSpec,
    showHidden: Boolean,
    themeMode: ThemeMode,
    onToggleSidebar: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onSetViewMode: (ViewMode) -> Unit,
    onSetSortKey: (SortKey) -> Unit,
    onToggleFoldersFirst: () -> Unit,
    onToggleShowHidden: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onReload: () -> Unit,
) {
    val colors = DangoTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.toolbar)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarIconButton(
            icon = Icons.Outlined.ViewSidebar,
            contentDescription = stringResource(R.string.cd_sidebar),
            onClick = onToggleSidebar,
        )
        ToolbarIconButton(
            icon = Icons.Rounded.ChevronLeft,
            contentDescription = stringResource(R.string.cd_back),
            onClick = onBack,
            enabled = canGoBack,
        )
        ToolbarIconButton(
            icon = Icons.Rounded.ChevronRight,
            contentDescription = stringResource(R.string.cd_forward),
            onClick = onForward,
            enabled = canGoForward,
        )
        Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
            color = colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ViewModeButton(ViewMode.ICON, Icons.Outlined.GridView, R.string.cd_view_icon, viewMode, onSetViewMode)
        ViewModeButton(ViewMode.LIST, Icons.AutoMirrored.Outlined.ViewList, R.string.cd_view_list, viewMode, onSetViewMode)
        ViewModeButton(ViewMode.COLUMN, Icons.Outlined.ViewColumn, R.string.cd_view_column, viewMode, onSetViewMode, enabled = false)
        ViewModeButton(ViewMode.GALLERY, Icons.Outlined.Collections, R.string.cd_view_gallery, viewMode, onSetViewMode, enabled = false)
        SortMenuButton(sort, onSetSortKey, onToggleFoldersFirst)
        OverflowMenuButton(showHidden, themeMode, onToggleShowHidden, onSetThemeMode, onReload)
    }
}

@Composable
private fun ToolbarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = DangoTheme.colors
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(38.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) colors.textPrimary else colors.textSecondary.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ViewModeButton(
    mode: ViewMode,
    icon: ImageVector,
    descriptionRes: Int,
    current: ViewMode,
    onSetViewMode: (ViewMode) -> Unit,
    enabled: Boolean = true,
) {
    val colors = DangoTheme.colors
    val selected = current == mode
    IconButton(
        onClick = { onSetViewMode(mode) },
        enabled = enabled,
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.selectionUnfocused else colors.toolbar),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(descriptionRes),
            tint = when {
                !enabled -> colors.textSecondary.copy(alpha = 0.35f)
                selected -> colors.textPrimary
                else -> colors.textSecondary
            },
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
private fun SortMenuButton(
    sort: SortSpec,
    onSetSortKey: (SortKey) -> Unit,
    onToggleFoldersFirst: () -> Unit,
) {
    val colors = DangoTheme.colors
    var expanded by remember { mutableStateOf(false) }
    ToolbarIconButton(
        icon = Icons.AutoMirrored.Outlined.Sort,
        contentDescription = stringResource(R.string.cd_sort),
        onClick = { expanded = true },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        val items = listOf(
            SortKey.NAME to R.string.sort_name,
            SortKey.KIND to R.string.sort_kind,
            SortKey.SIZE to R.string.sort_size,
            SortKey.DATE to R.string.sort_date,
        )
        items.forEach { (key, labelRes) ->
            DropdownMenuItem(
                text = { Text(stringResource(labelRes)) },
                onClick = { onSetSortKey(key) },
                trailingIcon = if (sort.key == key) {
                    {
                        Icon(
                            imageVector = if (sort.ascending) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    null
                },
            )
        }
        HorizontalDivider(color = colors.divider)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.sort_folders_first)) },
            onClick = onToggleFoldersFirst,
            trailingIcon = if (sort.foldersFirst) {
                { Icon(Icons.Outlined.Check, null, modifier = Modifier.size(16.dp)) }
            } else {
                null
            },
        )
    }
}

@Composable
private fun OverflowMenuButton(
    showHidden: Boolean,
    themeMode: ThemeMode,
    onToggleShowHidden: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onReload: () -> Unit,
) {
    val colors = DangoTheme.colors
    var expanded by remember { mutableStateOf(false) }
    ToolbarIconButton(
        icon = Icons.Outlined.MoreHoriz,
        contentDescription = stringResource(R.string.cd_more),
        onClick = { expanded = true },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_show_hidden)) },
            onClick = onToggleShowHidden,
            trailingIcon = if (showHidden) {
                { Icon(Icons.Outlined.Check, null, modifier = Modifier.size(16.dp)) }
            } else {
                null
            },
        )
        HorizontalDivider(color = colors.divider)
        Text(
            text = stringResource(R.string.menu_theme),
            color = colors.textSecondary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        val themes = listOf(
            ThemeMode.SYSTEM to R.string.theme_system,
            ThemeMode.LIGHT to R.string.theme_light,
            ThemeMode.DARK to R.string.theme_dark,
        )
        themes.forEach { (mode, labelRes) ->
            DropdownMenuItem(
                text = { Text(stringResource(labelRes)) },
                onClick = { onSetThemeMode(mode) },
                trailingIcon = if (themeMode == mode) {
                    { Icon(Icons.Outlined.Check, null, modifier = Modifier.size(16.dp)) }
                } else {
                    null
                },
            )
        }
        HorizontalDivider(color = colors.divider)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_reload)) },
            onClick = {
                expanded = false
                onReload()
            },
            leadingIcon = { Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp)) },
        )
    }
}
