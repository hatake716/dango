package io.github.hatake716.dango.ui.browser.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
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
import io.github.hatake716.dango.ui.theme.DangoMotion
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
    selectionMode: Boolean,
    selectionCount: Int,
    isTrash: Boolean,
    hasClipboard: Boolean,
    onToggleSidebar: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onSetViewMode: (ViewMode) -> Unit,
    onSetSortKey: (SortKey) -> Unit,
    onToggleFoldersFirst: () -> Unit,
    onToggleShowHidden: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onReload: () -> Unit,
    onExitSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onStartSelection: () -> Unit,
    onNewFolder: () -> Unit,
    onNewTextFile: (String) -> Unit,
    onPaste: () -> Unit,
    onEmptyTrash: () -> Unit,
    searchActive: Boolean = false,
    searchQuery: String = "",
    searchGlobal: Boolean = false,
    onEnterSearch: () -> Unit = {},
    onExitSearch: () -> Unit = {},
    onSearchQuery: (String) -> Unit = {},
    onToggleSearchGlobal: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val colors = DangoTheme.colors
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.toolbar)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
    // 幅が狭いと表示切替を1ボタンに畳み、フォルダ名に幅を回す（Finder と同じ振る舞い）
    val compact = maxWidth < COMPACT_TOOLBAR_WIDTH
    val mode = when {
        searchActive -> ToolbarMode.SEARCH
        selectionMode -> ToolbarMode.SELECTION
        else -> ToolbarMode.NORMAL
    }
    // 通常 / 選択 / 検索 の切替は短いクロスフェード
    AnimatedContent(
        targetState = mode,
        transitionSpec = { fadeIn(DangoMotion.fade()).togetherWith(fadeOut(DangoMotion.menuIn())) },
        label = "toolbarMode",
    ) { current ->
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (current == ToolbarMode.SEARCH) {
            // 検索モード（SPEC §6.7: 現在フォルダ/デバイス全体のトグル付き）
            ToolbarIconButton(
                icon = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.cancel),
                onClick = onExitSearch,
            )
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            // Finder の検索フィールド: 角丸の薄いグレー地に虫眼鏡、入力があれば ⊗ で消去
            val dark = colors.windowBackground.luminance() < 0.5f
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.textPrimary.copy(alpha = if (dark) 0.10f else 0.06f))
                    .padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQuery,
                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp, letterSpacing = (-0.2).sp),
                    cursorBrush = SolidColor(colors.selectionFocused),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    stringResource(R.string.search_hint),
                                    color = colors.textSecondary,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                )
                if (searchQuery.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { onSearchQuery("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Cancel,
                            contentDescription = stringResource(R.string.cd_clear_search),
                            tint = colors.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            SearchScopeToggle(global = searchGlobal, onToggle = onToggleSearchGlobal)
        } else if (current == ToolbarMode.SELECTION) {
            ToolbarIconButton(
                icon = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.cd_exit_selection),
                onClick = onExitSelection,
            )
            Text(
                text = stringResource(R.string.sel_count, selectionCount),
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
            ToolbarIconButton(
                icon = Icons.Outlined.SelectAll,
                contentDescription = stringResource(R.string.cd_select_all),
                onClick = onSelectAll,
            )
            SelectionMenuButton(onInvertSelection)
        } else {
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
                overflow = TextOverflow.MiddleEllipsis,
            )
            if (compact) {
                ViewModePullDown(viewMode, onSetViewMode)
            } else {
                ViewModeSegmented(viewMode, onSetViewMode)
            }
            ToolbarIconButton(
                icon = Icons.Outlined.Search,
                contentDescription = stringResource(R.string.cd_search),
                onClick = onEnterSearch,
            )
            SortMenuButton(sort, onSetSortKey, onToggleFoldersFirst)
            OverflowMenuButton(
                showHidden = showHidden,
                themeMode = themeMode,
                isTrash = isTrash,
                hasClipboard = hasClipboard,
                onToggleShowHidden = onToggleShowHidden,
                onSetThemeMode = onSetThemeMode,
                onReload = onReload,
                onStartSelection = onStartSelection,
                onNewFolder = onNewFolder,
                onNewTextFile = onNewTextFile,
                onPaste = onPaste,
                onEmptyTrash = onEmptyTrash,
                onOpenSettings = onOpenSettings,
            )
        }
    }
    }
    }
}

private enum class ToolbarMode { NORMAL, SELECTION, SEARCH }

private val COMPACT_TOOLBAR_WIDTH = 560.dp

/**
 * 検索範囲（このフォルダ / このデバイス）の小さなセグメント。
 * Finder の検索範囲バー「検索: このMac | "フォルダ"」に相当する
 */
@Composable
private fun SearchScopeToggle(global: Boolean, onToggle: () -> Unit) {
    val colors = DangoTheme.colors
    val dark = colors.windowBackground.luminance() < 0.5f
    val options = listOf(
        stringResource(R.string.search_scope_folder) to false,
        stringResource(R.string.search_scope_device) to true,
    )
    Row(
        modifier = Modifier
            .padding(end = 4.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(colors.textPrimary.copy(alpha = if (dark) 0.10f else 0.06f))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (label, isGlobal) ->
            val selected = global == isGlobal
            val bg by animateColorAsState(
                targetValue = when {
                    !selected -> Color.Transparent
                    dark -> colors.selectionUnfocused
                    else -> colors.windowBackground
                },
                animationSpec = DangoMotion.fade(),
                label = "scopeBg",
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .then(if (selected && !dark) Modifier.shadow(1.dp, RoundedCornerShape(6.dp)) else Modifier)
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg)
                    .selectable(selected = selected, role = Role.Tab) { if (!selected) onToggle() }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (selected) colors.textPrimary else colors.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SelectionMenuButton(onInvertSelection: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolbarIconButton(
            icon = Icons.Outlined.MoreHoriz,
            contentDescription = stringResource(R.string.cd_more),
            onClick = { expanded = true },
        )
        FinderMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FinderMenuItem(
                text = stringResource(R.string.menu_invert_selection),
                onClick = {
                    expanded = false
                    onInvertSelection()
                },
            )
        }
    }
}

/**
 * ツールバーのボタン（Finder 風）。見た目の押下ハイライトは角丸 36×32dp、
 * タップ判定はそれより広い 40×48dp（SPEC §12 の最小タップ領域）
 */
@Composable
private fun ToolbarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = DangoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val tint by animateColorAsState(
        targetValue = if (enabled) colors.textPrimary else colors.textSecondary.copy(alpha = 0.4f),
        animationSpec = DangoMotion.fade(),
        label = "toolbarTint",
    )
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 48.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 32.dp)
                .clip(ToolbarButtonShape)
                .indication(interaction, LocalIndication.current),
        )
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

private val ToolbarButtonShape = RoundedCornerShape(7.dp)

private data class ViewModeSpec(val mode: ViewMode, val icon: ImageVector, val descriptionRes: Int)

private val VIEW_MODES = listOf(
    ViewModeSpec(ViewMode.ICON, Icons.Outlined.GridView, R.string.cd_view_icon),
    ViewModeSpec(ViewMode.LIST, Icons.AutoMirrored.Outlined.ViewList, R.string.cd_view_list),
    ViewModeSpec(ViewMode.COLUMN, Icons.Outlined.ViewColumn, R.string.cd_view_column),
    ViewModeSpec(ViewMode.GALLERY, Icons.Outlined.Collections, R.string.cd_view_gallery),
)

private val SEGMENT_WIDTH = 34.dp
private val SEGMENT_HEIGHT = 28.dp

/**
 * 表示切替のセグメンテッドコントロール（Finder のツールバー「表示」）。
 * 薄いグレーのトラック上を、選択中の白いつまみが横にスライドする
 */
@Composable
private fun ViewModeSegmented(current: ViewMode, onSetViewMode: (ViewMode) -> Unit) {
    val colors = DangoTheme.colors
    val dark = colors.windowBackground.luminance() < 0.5f
    val track = colors.divider
    val thumb = if (dark) colors.selectionUnfocused else colors.windowBackground
    val index = VIEW_MODES.indexOfFirst { it.mode == current }.coerceAtLeast(0)
    val thumbX by animateDpAsState(
        targetValue = SEGMENT_WIDTH * index,
        animationSpec = DangoMotion.segment(),
        label = "segmentThumb",
    )
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(track)
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbX)
                .size(SEGMENT_WIDTH, SEGMENT_HEIGHT)
                .shadow(if (dark) 0.dp else 1.dp, RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
                .background(thumb),
        )
        Row {
            VIEW_MODES.forEach { spec ->
                val selected = spec.mode == current
                val iconTint by animateColorAsState(
                    targetValue = if (selected) colors.textPrimary else colors.textSecondary,
                    animationSpec = DangoMotion.fade(),
                    label = "segmentTint",
                )
                Box(
                    modifier = Modifier
                        .size(SEGMENT_WIDTH, SEGMENT_HEIGHT)
                        .clip(RoundedCornerShape(6.dp))
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onSetViewMode(spec.mode) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = spec.icon,
                        contentDescription = stringResource(spec.descriptionRes),
                        tint = iconTint,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

/**
 * 幅が足りないときの表示切替（Finder もウインドウが狭いと「表示」を
 * 現在の表示アイコン＋▾ の1ボタンのプルダウンに畳む）
 */
@Composable
private fun ViewModePullDown(current: ViewMode, onSetViewMode: (ViewMode) -> Unit) {
    val colors = DangoTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val spec = VIEW_MODES.first { it.mode == current }
    val interaction = remember { MutableInteractionSource() }
    Box {
        Box(
            modifier = Modifier
                .size(width = 50.dp, height = 48.dp)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.DropdownList,
                    onClick = { expanded = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 46.dp, height = 32.dp)
                    .clip(ToolbarButtonShape)
                    .indication(interaction, LocalIndication.current),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = spec.icon,
                    contentDescription = stringResource(R.string.cd_view_mode, stringResource(spec.descriptionRes)),
                    tint = colors.textPrimary,
                    modifier = Modifier.size(19.dp),
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        FinderMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VIEW_MODES.forEach { item ->
                FinderMenuItem(
                    text = stringResource(item.descriptionRes),
                    leadingIcon = item.icon,
                    checked = item.mode == current,
                    onClick = {
                        expanded = false
                        onSetViewMode(item.mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun SortMenuButton(
    sort: SortSpec,
    onSetSortKey: (SortKey) -> Unit,
    onToggleFoldersFirst: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolbarIconButton(
            icon = Icons.AutoMirrored.Outlined.Sort,
            contentDescription = stringResource(R.string.cd_sort),
            onClick = { expanded = true },
        )
        FinderMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val items = listOf(
                SortKey.NAME to R.string.sort_name,
                SortKey.KIND to R.string.sort_kind,
                SortKey.SIZE to R.string.sort_size,
                SortKey.DATE to R.string.sort_date,
            )
            items.forEach { (key, labelRes) ->
                FinderMenuItem(
                    text = stringResource(labelRes),
                    checked = sort.key == key,
                    onClick = { onSetSortKey(key) },
                    trailingIcon = if (sort.key == key) {
                        if (sort.ascending) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward
                    } else {
                        null
                    },
                )
            }
            FinderMenuDivider()
            FinderMenuItem(
                text = stringResource(R.string.sort_folders_first),
                checked = sort.foldersFirst,
                onClick = onToggleFoldersFirst,
            )
        }
    }
}

@Composable
private fun OverflowMenuButton(
    showHidden: Boolean,
    themeMode: ThemeMode,
    isTrash: Boolean,
    hasClipboard: Boolean,
    onToggleShowHidden: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onReload: () -> Unit,
    onStartSelection: () -> Unit,
    onNewFolder: () -> Unit,
    onNewTextFile: (String) -> Unit,
    onPaste: () -> Unit,
    onEmptyTrash: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolbarIconButton(
            icon = Icons.Outlined.MoreHoriz,
            contentDescription = stringResource(R.string.cd_more),
            onClick = { expanded = true },
        )
        FinderMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (!isTrash) {
                FinderMenuItem(
                    text = stringResource(R.string.menu_new_folder),
                    leadingIcon = Icons.Outlined.CreateNewFolder,
                    onClick = {
                        expanded = false
                        onNewFolder()
                    },
                )
                FinderMenuItem(
                    text = stringResource(R.string.menu_new_text),
                    leadingIcon = Icons.Outlined.NoteAdd,
                    onClick = {
                        expanded = false
                        onNewTextFile("txt")
                    },
                )
                FinderMenuItem(
                    text = stringResource(R.string.menu_new_markdown),
                    leadingIcon = Icons.Outlined.NoteAdd,
                    onClick = {
                        expanded = false
                        onNewTextFile("md")
                    },
                )
                if (hasClipboard) {
                    FinderMenuItem(
                        text = stringResource(R.string.clip_paste),
                        leadingIcon = Icons.Outlined.ContentPaste,
                        onClick = {
                            expanded = false
                            onPaste()
                        },
                    )
                }
                FinderMenuDivider()
            }
            FinderMenuItem(
                text = stringResource(R.string.menu_select),
                leadingIcon = Icons.Outlined.SelectAll,
                onClick = {
                    expanded = false
                    onStartSelection()
                },
            )
            if (isTrash) {
                FinderMenuItem(
                    text = stringResource(R.string.menu_empty_trash),
                    leadingIcon = Icons.Outlined.DeleteForever,
                    destructive = true,
                    onClick = {
                        expanded = false
                        onEmptyTrash()
                    },
                )
            }
            FinderMenuDivider()
            FinderMenuItem(
                text = stringResource(R.string.menu_show_hidden),
                checked = showHidden,
                onClick = onToggleShowHidden,
            )
            FinderMenuDivider()
            FinderMenuHeader(stringResource(R.string.menu_theme))
            val themes = listOf(
                ThemeMode.SYSTEM to R.string.theme_system,
                ThemeMode.LIGHT to R.string.theme_light,
                ThemeMode.DARK to R.string.theme_dark,
            )
            themes.forEach { (mode, labelRes) ->
                FinderMenuItem(
                    text = stringResource(labelRes),
                    checked = themeMode == mode,
                    onClick = { onSetThemeMode(mode) },
                )
            }
            FinderMenuDivider()
            FinderMenuItem(
                text = stringResource(R.string.menu_reload),
                leadingIcon = Icons.Outlined.Refresh,
                onClick = {
                    expanded = false
                    onReload()
                },
            )
            FinderMenuItem(
                text = stringResource(R.string.menu_settings),
                leadingIcon = Icons.Outlined.Settings,
                onClick = {
                    expanded = false
                    onOpenSettings()
                },
            )
        }
    }
}
