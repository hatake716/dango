package io.github.hatake716.dango.ui.browser

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.fs.local.ShareHelper
import io.github.hatake716.dango.data.prefs.Settings
import io.github.hatake716.dango.domain.model.ThemeMode
import io.github.hatake716.dango.domain.model.ViewMode
import io.github.hatake716.dango.ui.browser.components.BatchRenameDialog
import io.github.hatake716.dango.ui.browser.components.BottomActionBar
import io.github.hatake716.dango.ui.browser.components.ClipboardBar
import io.github.hatake716.dango.ui.browser.components.ConflictDialog
import io.github.hatake716.dango.ui.browser.components.DangoToolbar
import io.github.hatake716.dango.ui.browser.components.DeleteConfirmDialog
import io.github.hatake716.dango.ui.browser.components.FileListView
import io.github.hatake716.dango.ui.browser.components.IconGridView
import io.github.hatake716.dango.ui.browser.components.PathBar
import io.github.hatake716.dango.ui.browser.components.SidebarContent
import io.github.hatake716.dango.ui.browser.components.StatusBar
import io.github.hatake716.dango.ui.info.InfoSheet
import io.github.hatake716.dango.ui.quicklook.QuickLookHost
import io.github.hatake716.dango.ui.theme.DangoTheme

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    hasFullAccess: Boolean,
    onRequestFullAccess: () -> Unit,
) {
    val colors = DangoTheme.colors
    val state by viewModel.state.collectAsState()
    val settings = viewModel.settings.collectAsState().value ?: Settings()
    val transfer by viewModel.transferProgress.collectAsState()
    val conflict by viewModel.conflictRequest.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var sidebarOpen by remember { mutableStateOf(false) }

    // 転送通知の実行時許可（SPEC §11: 初回転送時）
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    var notifAsked by rememberSaveable { mutableStateOf(false) }
    fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !notifAsked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifAsked = true
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is BrowserEvent.Message ->
                    snackbarHostState.showSnackbar(context.getString(event.res))
                is BrowserEvent.TrashDone -> {
                    val result = snackbarHostState.showSnackbar(
                        message = context.getString(R.string.trash_done, event.count),
                        actionLabel = context.getString(R.string.undo),
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.undoTrash(event.undoIds)
                    }
                }
                is BrowserEvent.Share ->
                    runCatching {
                        context.startActivity(ShareHelper.shareIntent(context, event.entries))
                    }
                is BrowserEvent.OpenWith ->
                    runCatching {
                        context.startActivity(ShareHelper.openWithIntent(context, event.entry))
                    }
            }
        }
    }

    // 戻る操作の優先順位: Quick Look → リネーム → 選択モード → サイドバー → 履歴
    BackHandler(
        enabled = state.quickLookIndex != null || state.renamingKey != null ||
            state.selectionMode || sidebarOpen || state.canGoBack,
    ) {
        when {
            state.quickLookIndex != null -> viewModel.closeQuickLook()
            state.renamingKey != null -> viewModel.cancelRename()
            state.selectionMode -> viewModel.exitSelectionMode()
            sidebarOpen -> sidebarOpen = false
            else -> viewModel.goBack()
        }
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
                    onEnsureNotifPermission = ::ensureNotifPermission,
                    transfer = transfer,
                    snackbarHostState = snackbarHostState,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            MainPane(
                viewModel = viewModel,
                state = state,
                themeMode = settings.themeMode,
                hasFullAccess = hasFullAccess,
                onRequestFullAccess = onRequestFullAccess,
                onToggleSidebar = { sidebarOpen = !sidebarOpen },
                onEnsureNotifPermission = ::ensureNotifPermission,
                transfer = transfer,
                snackbarHostState = snackbarHostState,
                modifier = Modifier.fillMaxSize(),
            )
            // 縦持ちのサイドバー: コンテンツに重なるオーバーレイ（SPEC §4.1, §5: 220ms スライド）
            AnimatedVisibility(
                visible = sidebarOpen,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(220)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { sidebarOpen = false },
                )
            }
            AnimatedVisibility(
                visible = sidebarOpen,
                enter = slideInHorizontally(tween(220)) { -it },
                exit = slideOutHorizontally(tween(220)) { -it },
            ) {
                Row {
                    SidebarContent(
                        favorites = viewModel.sidebarFavorites,
                        locations = viewModel.sidebarLocations,
                        currentPath = state.currentPath,
                        onNavigate = {
                            viewModel.navigateTo(it)
                            sidebarOpen = false
                        },
                        modifier = Modifier.width(268.dp),
                    )
                    VerticalDivider(color = colors.divider, modifier = Modifier.fillMaxHeight())
                }
            }
        }

        // Quick Look（SPEC §5: 280ms spring。共有要素は近似）
        // 閉じるアニメーション中もコンテンツを保持するため、最後の表示内容を覚えておく
        var lastQuickLook by remember {
            mutableStateOf<Pair<List<io.github.hatake716.dango.domain.model.FsEntry>, Int>?>(null)
        }
        val qlIndex = state.quickLookIndex
        if (qlIndex != null && state.quickLookFiles.isNotEmpty()) {
            lastQuickLook = state.quickLookFiles to qlIndex
        }
        AnimatedVisibility(
            visible = state.quickLookIndex != null,
            enter = fadeIn() + scaleIn(
                initialScale = 0.82f,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
            ),
            exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.9f, animationSpec = tween(180)),
        ) {
            lastQuickLook?.let { (files, index) ->
                QuickLookHost(
                    files = files,
                    index = index,
                    textFileStore = viewModel.textFileStore,
                    onIndexChange = viewModel::setQuickLookIndex,
                    onClose = viewModel::closeQuickLook,
                    onShare = viewModel::shareEntry,
                    onOpenWith = viewModel::openWith,
                    onInfo = viewModel::showInfo,
                    onNotify = viewModel::notify,
                )
            }
        }
    }

    // --- ダイアログ・シート ---

    conflict?.let { ConflictDialog(it) }

    state.pendingDelete?.let { pending ->
        DeleteConfirmDialog(
            count = pending.entries.size,
            emptyAll = pending.emptyAll,
            onConfirm = viewModel::confirmPendingDelete,
            onDismiss = viewModel::dismissPendingDelete,
        )
    }

    if (state.pendingBatchRename) {
        BatchRenameDialog(
            firstName = viewModel.selectedEntries().firstOrNull()?.name ?: "",
            onApply = viewModel::applyBatchRename,
            onDismiss = viewModel::dismissBatchRename,
        )
    }

    state.infoTarget?.let { target ->
        InfoSheet(
            entry = target,
            infoLoader = viewModel.infoLoader,
            onDismiss = viewModel::closeInfo,
        )
    }
}

@Composable
private fun MainPane(
    viewModel: BrowserViewModel,
    state: BrowserUiState,
    themeMode: ThemeMode,
    hasFullAccess: Boolean,
    onRequestFullAccess: () -> Unit,
    onToggleSidebar: () -> Unit,
    onEnsureNotifPermission: () -> Unit,
    transfer: io.github.hatake716.dango.data.transfer.TransferProgress?,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val colors = DangoTheme.colors
    val selectedEntries = viewModel.selectedEntries()
    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            DangoToolbar(
                title = when {
                    state.isTrash -> stringResource(R.string.loc_trash)
                    state.currentPath == viewModel.internalRoot -> stringResource(R.string.loc_internal)
                    else -> state.currentPath.name
                },
                canGoBack = state.canGoBack,
                canGoForward = state.canGoForward,
                viewMode = state.viewMode,
                sort = state.sort,
                showHidden = state.showHidden,
                themeMode = themeMode,
                selectionMode = state.selectionMode,
                selectionCount = state.selection.size,
                isTrash = state.isTrash,
                hasClipboard = state.clipboard != null,
                onToggleSidebar = onToggleSidebar,
                onBack = viewModel::goBack,
                onForward = viewModel::goForward,
                onSetViewMode = viewModel::setViewMode,
                onSetSortKey = viewModel::setSortKey,
                onToggleFoldersFirst = viewModel::toggleFoldersFirst,
                onToggleShowHidden = viewModel::toggleShowHidden,
                onSetThemeMode = viewModel::setThemeMode,
                onReload = viewModel::reload,
                onExitSelection = viewModel::exitSelectionMode,
                onSelectAll = viewModel::selectAll,
                onInvertSelection = viewModel::invertSelection,
                onStartSelection = viewModel::startSelectionMode,
                onNewFolder = viewModel::createFolder,
                onNewTextFile = viewModel::createTextFile,
                onPaste = {
                    onEnsureNotifPermission()
                    viewModel.paste()
                },
                onEmptyTrash = viewModel::requestEmptyTrash,
            )
            HorizontalDivider(color = colors.divider)
            if (!hasFullAccess) {
                NormalModeBanner(onRequestFullAccess)
            }
            state.clipboard?.let { clipboard ->
                if (!state.isTrash) {
                    ClipboardBar(
                        clipboard = clipboard,
                        enabled = transfer == null,
                        onPaste = {
                            onEnsureNotifPermission()
                            viewModel.paste()
                        },
                        onClear = viewModel::clearClipboard,
                    )
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                ContentArea(viewModel, state)
            }
            BottomActionBar(
                visible = state.selectionMode,
                isTrash = state.isTrash,
                selectionCount = state.selection.size,
                // 直接タップと同様、未対応形式もフォールバックページで開ける
                canPreview = selectedEntries.size == 1 && !selectedEntries.first().isDir &&
                    !selectedEntries.first().isRestricted,
                onPreview = { selectedEntries.firstOrNull()?.let(viewModel::openQuickLook) },
                onShare = viewModel::shareSelected,
                onCopy = viewModel::copySelected,
                onMove = viewModel::cutSelected,
                onDuplicate = viewModel::duplicateSelected,
                onRename = viewModel::startRename,
                onDelete = viewModel::deleteSelected,
                onInfo = viewModel::showInfoForSelection,
                onRestore = viewModel::restoreSelected,
            )
            HorizontalDivider(color = colors.divider)
            PathBar(
                currentPath = state.currentPath,
                internalRoot = viewModel.internalRoot,
                onNavigate = { viewModel.navigateTo(it, NavDirection.BACKWARD) },
                onPathCopied = { viewModel.notify(R.string.path_copied) },
            )
            HorizontalDivider(color = colors.divider)
            StatusBar(
                itemCount = if (state.viewMode == ViewMode.LIST) state.listRows.size else state.entries.size,
                selectedCount = state.selection.size,
                freeSpaceBytes = state.freeSpaceBytes,
                transfer = transfer,
                onCancelTransfer = viewModel::cancelTransfer,
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp),
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
                        text = stringResource(
                            if (paneState.isTrash) R.string.trash_empty else R.string.empty_folder,
                        ),
                        color = colors.textSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                paneState.viewMode == ViewMode.LIST -> {
                    FileListView(
                        rows = paneState.listRows,
                        selection = paneState.selection,
                        sort = paneState.sort,
                        renamingKey = paneState.renamingKey,
                        pastedKeys = paneState.pastedKeys,
                        onTap = viewModel::onEntryTap,
                        onDoubleTap = viewModel::onEntryDoubleTap,
                        onLongPress = viewModel::onEntryLongPress,
                        onToggleExpand = viewModel::toggleExpand,
                        onSetSortKey = viewModel::setSortKey,
                        onCommitRename = viewModel::commitRename,
                        onCancelRename = viewModel::cancelRename,
                        showExpanders = !paneState.isTrash,
                    )
                }
                else -> {
                    IconGridView(
                        entries = paneState.entries,
                        selection = paneState.selection,
                        iconSizeDp = paneState.iconSizeDp,
                        renamingKey = paneState.renamingKey,
                        pastedKeys = paneState.pastedKeys,
                        onTap = viewModel::onEntryTap,
                        onDoubleTap = viewModel::onEntryDoubleTap,
                        onLongPress = viewModel::onEntryLongPress,
                        onPinchZoom = viewModel::scaleIconSize,
                        onCommitRename = viewModel::commitRename,
                        onCancelRename = viewModel::cancelRename,
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
                color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
            )
        }
    }
    HorizontalDivider(color = colors.divider)
}
