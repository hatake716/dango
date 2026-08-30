package io.github.hatake716.dango.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.prefs.Settings
import io.github.hatake716.dango.domain.model.ViewMode
import io.github.hatake716.dango.ui.browser.components.DangoToolbar
import io.github.hatake716.dango.ui.browser.components.FileListView
import io.github.hatake716.dango.ui.browser.components.IconGridView
import io.github.hatake716.dango.ui.browser.components.PathBar
import io.github.hatake716.dango.ui.browser.components.SidebarContent
import io.github.hatake716.dango.ui.browser.components.StatusBar
import io.github.hatake716.dango.ui.theme.DangoTheme
import kotlinx.coroutines.launch

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    hasFullAccess: Boolean,
    onRequestFullAccess: () -> Unit,
) {
    val colors = DangoTheme.colors
    val state by viewModel.state.collectAsState()
    val settings = viewModel.settings.collectAsState().value ?: Settings()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.messages.collect { resId ->
            snackbarHostState.showSnackbar(context.getString(resId))
        }
    }

    BackHandler(enabled = state.canGoBack) {
        viewModel.goBack()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.windowBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        val isWide = maxWidth >= 600.dp
        if (isWide) {
            // 幅600dp以上はサイドバー常時表示。トグルで開閉できる（SPEC §4.1, §5: 220ms）
            var sidebarVisible by rememberSaveable { mutableStateOf(true) }
            Row(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = sidebarVisible,
                    enter = expandHorizontally(tween(220)) + fadeIn(tween(220)),
                    exit = shrinkHorizontally(tween(220)) + fadeOut(tween(220)),
                ) {
                    Row {
                        SidebarContent(
                            favorites = viewModel.sidebarFavorites,
                            locations = viewModel.sidebarLocations,
                            currentPath = state.currentPath,
                            onNavigate = { viewModel.navigateTo(it) },
                            modifier = Modifier.width(220.dp),
                        )
                        VerticalDivider(color = colors.divider, modifier = Modifier.fillMaxHeight())
                    }
                }
                MainPane(
                    viewModel = viewModel,
                    state = state,
                    themeMode = settings.themeMode,
                    hasFullAccess = hasFullAccess,
                    onRequestFullAccess = onRequestFullAccess,
                    onToggleSidebar = { sidebarVisible = !sidebarVisible },
                    snackbarHostState = snackbarHostState,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerShape = RectangleShape,
                        drawerContainerColor = colors.sidebar,
                        modifier = Modifier.width(270.dp),
                    ) {
                        SidebarContent(
                            favorites = viewModel.sidebarFavorites,
                            locations = viewModel.sidebarLocations,
                            currentPath = state.currentPath,
                            onNavigate = {
                                viewModel.navigateTo(it)
                                scope.launch { drawerState.close() }
                            },
                        )
                    }
                },
            ) {
                MainPane(
                    viewModel = viewModel,
                    state = state,
                    themeMode = settings.themeMode,
                    hasFullAccess = hasFullAccess,
                    onRequestFullAccess = onRequestFullAccess,
                    onToggleSidebar = {
                        scope.launch { if (drawerState.isClosed) drawerState.open() else drawerState.close() }
                    },
                    snackbarHostState = snackbarHostState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun MainPane(
    viewModel: BrowserViewModel,
    state: BrowserUiState,
    themeMode: io.github.hatake716.dango.domain.model.ThemeMode,
    hasFullAccess: Boolean,
    onRequestFullAccess: () -> Unit,
    onToggleSidebar: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val colors = DangoTheme.colors
    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            DangoToolbar(
                title = if (state.currentPath == viewModel.internalRoot) {
                    stringResource(R.string.loc_internal)
                } else {
                    state.currentPath.name
                },
                canGoBack = state.canGoBack,
                canGoForward = state.canGoForward,
                viewMode = state.viewMode,
                sort = state.sort,
                showHidden = state.showHidden,
                themeMode = themeMode,
                onToggleSidebar = onToggleSidebar,
                onBack = viewModel::goBack,
                onForward = viewModel::goForward,
                onSetViewMode = viewModel::setViewMode,
                onSetSortKey = viewModel::setSortKey,
                onToggleFoldersFirst = viewModel::toggleFoldersFirst,
                onToggleShowHidden = viewModel::toggleShowHidden,
                onSetThemeMode = viewModel::setThemeMode,
                onReload = viewModel::reload,
            )
            HorizontalDivider(color = colors.divider)
            if (!hasFullAccess) {
                NormalModeBanner(onRequestFullAccess)
            }
            Box(modifier = Modifier.weight(1f)) {
                ContentArea(viewModel, state)
            }
            HorizontalDivider(color = colors.divider)
            PathBar(
                currentPath = state.currentPath,
                internalRoot = viewModel.internalRoot,
                onNavigate = { viewModel.navigateTo(it, NavDirection.BACKWARD) },
                onPathCopied = { viewModel.notify(R.string.path_copied) },
            )
            HorizontalDivider(color = colors.divider)
            StatusBar(
                itemCount = state.entries.size,
                selectedCount = state.selection.size,
                freeSpaceBytes = state.freeSpaceBytes,
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp),
        )
    }
}

@Composable
private fun ContentArea(
    viewModel: BrowserViewModel,
    state: BrowserUiState,
) {
    val colors = DangoTheme.colors
    // フォルダを開く/戻るのズームアニメーション（SPEC §5: 200ms, EaseOutCubic。
    // 新は 0.96→1.0 で拡大フェードイン、旧は縮小フェードアウト。戻るは逆再生＝同一の対称形）
    AnimatedContent(
        targetState = state.currentPath,
        transitionSpec = {
            val spec = tween<Float>(durationMillis = 200, easing = EaseOutCubic)
            when (state.navDirection) {
                NavDirection.JUMP ->
                    fadeIn(spec).togetherWith(fadeOut(spec))
                else ->
                    (fadeIn(spec) + scaleIn(initialScale = 0.96f, animationSpec = spec))
                        .togetherWith(fadeOut(spec) + scaleOut(targetScale = 0.96f, animationSpec = spec))
            }
        },
        label = "folderTransition",
        modifier = Modifier.fillMaxSize(),
    ) { panePath ->
        // 退場側ペインが最新 state を読むと新旧が同一内容になりズームが見えなくなるため、
        // このペインが現在パスである間だけ状態を追従させ、退場中は最後の状態で凍結する
        var frozen by remember { mutableStateOf(state) }
        if (panePath == state.currentPath && frozen !== state) {
            frozen = state
        }
        val paneState = frozen
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                paneState.loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = colors.accent,
                    )
                }
                paneState.errorRes != null -> {
                    Text(
                        text = stringResource(paneState.errorRes),
                        color = colors.textSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                paneState.entries.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.empty_folder),
                        color = colors.textSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                paneState.viewMode == ViewMode.LIST -> {
                    FileListView(
                        entries = paneState.entries,
                        selection = paneState.selection,
                        sort = paneState.sort,
                        onTap = viewModel::onEntryTap,
                        onDoubleTap = viewModel::onEntryDoubleTap,
                        onLongPress = viewModel::onEntryLongPress,
                        onSetSortKey = viewModel::setSortKey,
                    )
                }
                else -> {
                    IconGridView(
                        entries = paneState.entries,
                        selection = paneState.selection,
                        onTap = viewModel::onEntryTap,
                        onDoubleTap = viewModel::onEntryDoubleTap,
                        onLongPress = viewModel::onEntryLongPress,
                    )
                }
            }
        }
    }
}

@Composable
private fun NormalModeBanner(onRequestFullAccess: () -> Unit) {
    val colors = DangoTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.sidebar)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.normal_mode_banner),
            color = colors.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRequestFullAccess) {
            Text(
                text = stringResource(R.string.normal_mode_grant),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
            )
        }
    }
    HorizontalDivider(color = colors.divider)
}
