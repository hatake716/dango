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
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.fs.local.ShareHelper
import io.github.hatake716.dango.data.prefs.Settings
import io.github.hatake716.dango.domain.model.ThemeMode
import io.github.hatake716.dango.domain.model.ViewMode
import io.github.hatake716.dango.ui.browser.components.ArchivePasswordDialog
import io.github.hatake716.dango.ui.browser.components.BackgroundContextMenuContent
import io.github.hatake716.dango.ui.browser.components.ConnectionDialog
import io.github.hatake716.dango.ui.browser.components.NetPasswordDialog
import io.github.hatake716.dango.ui.browser.components.SettingsSheet
import io.github.hatake716.dango.ui.browser.components.BatchRenameDialog
import io.github.hatake716.dango.ui.browser.components.BottomActionBar
import io.github.hatake716.dango.ui.browser.components.ClipboardBar
import io.github.hatake716.dango.ui.browser.components.CompressDialog
import io.github.hatake716.dango.ui.browser.components.ConflictDialog
import io.github.hatake716.dango.ui.browser.components.DangoToolbar
import io.github.hatake716.dango.ui.browser.components.DeleteConfirmDialog
import io.github.hatake716.dango.ui.browser.components.EntryContextMenuContent
import io.github.hatake716.dango.ui.browser.components.EntryItemHooks
import io.github.hatake716.dango.ui.browser.components.EntryMenuActions
import io.github.hatake716.dango.ui.browser.components.ExtractOptionsDialog
import io.github.hatake716.dango.ui.browser.components.FileListView
import io.github.hatake716.dango.ui.browser.components.IconGridView
import io.github.hatake716.dango.ui.browser.components.PathBar
import io.github.hatake716.dango.ui.browser.components.SidebarContent
import io.github.hatake716.dango.ui.browser.components.StatusBar
import io.github.hatake716.dango.ui.browser.components.dragEndTracker
import io.github.hatake716.dango.ui.browser.components.openCloudLink
import io.github.hatake716.dango.ui.browser.components.onRightClick
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
    val connections by viewModel.connections.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var sidebarOpen by remember { mutableStateOf(false) }
    // ドラッグ中のエントリ（半透明表示。ドラッグ終了で解除）
    var draggingKeys by remember { mutableStateOf<Set<String>>(emptySet()) }

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

    // 戻る操作の優先順位: Quick Look → リネーム → 検索 → 選択モード → サイドバー → 履歴
    BackHandler(
        enabled = state.quickLookIndex != null || state.renamingKey != null ||
            state.searchActive || state.selectionMode || sidebarOpen || state.canGoBack,
    ) {
        when {
            state.quickLookIndex != null -> viewModel.closeQuickLook()
            state.renamingKey != null -> viewModel.cancelRename()
            state.searchActive -> viewModel.exitSearch()
            state.selectionMode -> viewModel.exitSelectionMode()
            sidebarOpen -> sidebarOpen = false
            else -> viewModel.goBack()
        }
    }

    // サイドバーのドロップ受け（ゴミ箱は削除、それ以外は移動。SPEC §4.3）
    val onSidebarDrop: (SidebarItem, Set<String>) -> Unit = { item, keys ->
        draggingKeys = emptySet()
        if (item.path == BrowserViewModel.TRASH_PATH) {
            viewModel.dropKeysToTrash(keys)
        } else {
            ensureNotifPermission()
            viewModel.moveByDrag(keys, item.path)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.windowBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .dragEndTracker { draggingKeys = emptySet() },
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
                            onDropKeys = onSidebarDrop,
                            connections = connections,
                            onOpenConnection = viewModel::openConnection,
                            onEditConnection = viewModel::requestEditConnection,
                            onAddConnection = viewModel::requestAddConnection,
                            tagColors = viewModel.tagColors,
                            onOpenTag = { viewModel.navigateTo(BrowserViewModel.tagPath(it)) },
                            onOpenCloudLink = { openCloudLink(context, it) },
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
                    draggingKeys = draggingKeys,
                    onDragStart = { draggingKeys = it },
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
                draggingKeys = draggingKeys,
                onDragStart = { draggingKeys = it },
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
                        onDropKeys = { item, keys ->
                            onSidebarDrop(item, keys)
                            sidebarOpen = false
                        },
                        connections = connections,
                        onOpenConnection = {
                            viewModel.openConnection(it)
                            sidebarOpen = false
                        },
                        onEditConnection = viewModel::requestEditConnection,
                        onAddConnection = viewModel::requestAddConnection,
                        tagColors = viewModel.tagColors,
                        onOpenTag = {
                            viewModel.navigateTo(BrowserViewModel.tagPath(it))
                            sidebarOpen = false
                        },
                        onOpenCloudLink = {
                            openCloudLink(context, it)
                            sidebarOpen = false
                        },
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
                    loadArchiveIndex = viewModel::archiveIndexFor,
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

    if (state.showCompressDialog) {
        CompressDialog(
            itemCount = state.selection.size,
            onApply = { format, level, password, deleteSource ->
                ensureNotifPermission()
                viewModel.compressSelected(format, level, password, deleteSource)
            },
            onDismiss = viewModel::dismissCompress,
        )
    }

    state.extractOptionsFor?.let { target ->
        ExtractOptionsDialog(
            entryName = target.name,
            onApply = { encoding, wrap ->
                viewModel.dismissExtractOptions()
                ensureNotifPermission()
                viewModel.extractArchive(
                    target,
                    wrapInFolder = wrap,
                    encodingOverride = encoding,
                    explicitEncoding = true,
                )
            },
            onDismiss = viewModel::dismissExtractOptions,
        )
    }

    state.archivePasswordAsk?.let { name ->
        ArchivePasswordDialog(
            archiveName = name,
            onSubmit = viewModel::submitArchivePassword,
            onCancel = viewModel::cancelArchivePassword,
        )
    }

    state.editingConnection?.let { connection ->
        ConnectionDialog(
            initial = connection,
            onSave = viewModel::saveConnection,
            onTest = viewModel::testConnection,
            onDelete = viewModel::deleteConnection,
            onDismiss = viewModel::dismissConnectionDialog,
        )
    }

    state.netPasswordAsk?.let { (_, name) ->
        NetPasswordDialog(
            connectionName = name,
            onSubmit = viewModel::submitNetPassword,
            onCancel = viewModel::cancelNetPassword,
        )
    }

    if (state.showSettings) {
        SettingsSheet(
            settings = settings,
            onSetThemeMode = viewModel::setThemeMode,
            onSetDynamicColor = viewModel::setDynamicColor,
            onSetSingleTap = viewModel::setSingleTapOpen,
            onSetTrashDays = viewModel::setTrashAutoDays,
            onClearCache = viewModel::clearCaches,
            onSetBiometric = viewModel::setBiometricLock,
            onDismiss = viewModel::dismissSettings,
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
    draggingKeys: Set<String>,
    onDragStart: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DangoTheme.colors
    val selectedEntries = viewModel.selectedEntries()

    // 右クリックメニュー（マウス操作。Finder のコンテキストメニュー相当）
    var contextMenuKey by remember { mutableStateOf<String?>(null) }
    var contextMenuOffset by remember { mutableStateOf(DpOffset.Zero) }
    var backgroundMenuAt by remember { mutableStateOf<DpOffset?>(null) }
    // フォルダ移動でメニュー状態を確実に破棄する（同キーのアイテム再表示で勝手に開かないように）
    LaunchedEffect(state.currentPath) {
        contextMenuKey = null
        backgroundMenuAt = null
    }

    val menuActions = remember(viewModel) {
        EntryMenuActions(
            onOpen = { entry ->
                if (entry.isDir) viewModel.navigateTo(entry.path) else viewModel.onEntryDoubleTap(entry)
            },
            onPreview = viewModel::openQuickLook,
            onBrowseArchive = viewModel::browseArchive,
            onExtractHere = { entry ->
                onEnsureNotifPermission()
                viewModel.extractArchive(entry, wrapInFolder = false)
            },
            onExtractOptions = viewModel::showExtractOptions,
            onExportEntry = viewModel::exportArchiveEntry,
            onShare = viewModel::shareEntry,
            onOpenWith = viewModel::openWith,
            onCopy = viewModel::copySelected,
            onCut = viewModel::cutSelected,
            onDuplicate = viewModel::duplicateSelected,
            onRename = viewModel::startRename,
            onCompress = viewModel::requestCompress,
            onDelete = viewModel::deleteSelected,
            onRestore = viewModel::restoreSelected,
            onInfo = viewModel::showInfo,
            onToggleTag = viewModel::toggleTag,
            onPasteInto = { target ->
                onEnsureNotifPermission()
                if (target != null) {
                    viewModel.paste(target.path)
                } else {
                    viewModel.paste()
                }
            },
            onSelectAll = viewModel::selectAll,
            dismiss = { contextMenuKey = null },
        )
    }

    val hooks = EntryItemHooks(
        draggingKeys = draggingKeys,
        contextMenuKey = contextMenuKey,
        contextMenuOffset = contextMenuOffset,
        onContextRequest = { entry, offset ->
            // 右クリックは Finder 同様、選択に含まれていなければその項目を単独選択する。
            // onEntryTap だと「シングルタップで開く」設定でファイルが開いてしまうため、
            // 開かない selectOnly を使う
            if (entry.path.key !in state.selection) {
                viewModel.selectOnly(entry)
            }
            contextMenuOffset = offset
            contextMenuKey = entry.path.key
        },
        onContextDismiss = { contextMenuKey = null },
        contextMenuContent = { entry ->
            EntryContextMenuContent(
                entry = entry,
                isTrash = state.isTrash,
                isArchiveBrowse = state.isArchive,
                hasClipboard = state.clipboard != null,
                entryTags = state.tagsByKey[entry.path.key] ?: emptySet(),
                actions = menuActions,
            )
        },
        dragKeysFor = { entry ->
            // ネットワーク上のD&Dは後回し（PROGRESS 参照。確認なしのリモート削除を防ぐ意味もある）
            if (state.isTrash || state.isArchive || state.isNetwork) {
                null
            } else if (entry.path.key in state.selection) {
                state.selection
            } else {
                setOf(entry.path.key)
            }
        },
        onDragStart = onDragStart,
        dropEnabled = { entry ->
            !state.isTrash && !state.isArchive && entry.isDir && !entry.isRestricted &&
                entry.path.key !in draggingKeys
        },
        onDropInto = { keys, entry ->
            onEnsureNotifPermission()
            viewModel.moveByDrag(keys, entry.path)
        },
    )

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
                searchActive = state.searchActive,
                searchQuery = state.searchQuery,
                searchGlobal = state.searchGlobal,
                onEnterSearch = viewModel::enterSearch,
                onExitSearch = viewModel::exitSearch,
                onSearchQuery = viewModel::setSearchQuery,
                onToggleSearchGlobal = { viewModel.setSearchGlobal(!state.searchGlobal) },
                onOpenSettings = viewModel::showSettings,
            )
            HorizontalDivider(color = colors.divider)
            if (!hasFullAccess) {
                NormalModeBanner(onRequestFullAccess)
            }
            state.clipboard?.let { clipboard ->
                if (!state.isTrash && !state.isArchive) {
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
                val density = LocalDensity.current
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // 何もない場所の右クリック。Main パスで待つことで、
                        // アイテム側が Initial で consume した右クリックを正しく無視できる
                        .onRightClick(
                            requireUnconsumed = true,
                            pass = androidx.compose.ui.input.pointer.PointerEventPass.Main,
                        ) { offset ->
                            backgroundMenuAt =
                                with(density) { DpOffset(offset.x.toDp(), offset.y.toDp()) }
                        },
                ) {
                    ContentArea(viewModel, state, hooks)
                    DropdownMenu(
                        expanded = backgroundMenuAt != null,
                        onDismissRequest = { backgroundMenuAt = null },
                        offset = backgroundMenuAt ?: DpOffset.Zero,
                    ) {
                        BackgroundContextMenuContent(
                            hasClipboard = state.clipboard != null,
                            isTrash = state.isTrash,
                            isArchiveBrowse = state.isArchive,
                            onNewFolder = viewModel::createFolder,
                            onNewTextFile = viewModel::createTextFile,
                            onPaste = {
                                onEnsureNotifPermission()
                                viewModel.paste()
                            },
                            onSelectAll = viewModel::selectAll,
                            onReload = viewModel::reload,
                            dismiss = { backgroundMenuAt = null },
                        )
                    }
                }
            }
            BottomActionBar(
                visible = state.selectionMode,
                isTrash = state.isTrash,
                selectionCount = state.selection.size,
                // 直接タップと同様、未対応形式もフォールバックページで開ける
                canPreview = selectedEntries.size == 1 && !selectedEntries.first().isDir &&
                    !selectedEntries.first().isRestricted,
                isSingleArchive = selectedEntries.size == 1 &&
                    selectedEntries.first().kind == io.github.hatake716.dango.domain.model.EntryKind.ARCHIVE,
                onExtract = {
                    selectedEntries.firstOrNull()?.let {
                        onEnsureNotifPermission()
                        viewModel.extractArchive(it, wrapInFolder = true)
                    }
                },
                onBrowseArchive = { selectedEntries.firstOrNull()?.let(viewModel::browseArchive) },
                onExtractOptions = { selectedEntries.firstOrNull()?.let(viewModel::showExtractOptions) },
                onPreview = { selectedEntries.firstOrNull()?.let(viewModel::openQuickLook) },
                onShare = viewModel::shareSelected,
                onCopy = viewModel::copySelected,
                onMove = viewModel::cutSelected,
                onDuplicate = viewModel::duplicateSelected,
                onRename = viewModel::startRename,
                onCompress = viewModel::requestCompress,
                onDelete = viewModel::deleteSelected,
                onInfo = viewModel::showInfoForSelection,
                onRestore = viewModel::restoreSelected,
            )
            HorizontalDivider(color = colors.divider)
            val connectionsForLabel by viewModel.connections.collectAsState()
            PathBar(
                currentPath = state.currentPath,
                internalRoot = viewModel.internalRoot,
                onNavigate = { viewModel.navigateTo(it, NavDirection.BACKWARD) },
                onPathCopied = { viewModel.notify(R.string.path_copied) },
                onDropKeys = { path, keys ->
                    onEnsureNotifPermission()
                    viewModel.moveByDrag(keys, path)
                },
                connectionLabel = { id -> connectionsForLabel.find { it.id == id }?.name },
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
    hooks: EntryItemHooks,
) {
    val colors = DangoTheme.colors
    // カラム表示は自前の横スクロールを持つため、ズームアニメーションの外で描画する（SPEC §4.4）。
    // ゴミ箱・タグ検索は仮想パスと実体パスがずれる（実体 .Trash へ入れてしまう）ためカラム対象外
    if (state.viewMode == ViewMode.COLUMN && !state.searchActive &&
        !state.isTrash && state.currentPath.scheme != BrowserViewModel.TAG_SCHEME
    ) {
        val base = remember(state.currentPath) {
            when {
                io.github.hatake716.dango.data.net.NetPaths.isNetwork(state.currentPath) ->
                    io.github.hatake716.dango.domain.model.FsPath(
                        state.currentPath.scheme,
                        state.currentPath.segments.take(1),
                    )
                state.currentPath.scheme == io.github.hatake716.dango.data.archive.ArchivePaths.SCHEME ->
                    io.github.hatake716.dango.domain.model.FsPath(
                        state.currentPath.scheme,
                        state.currentPath.segments.take(1),
                    )
                state.currentPath.scheme == "file" &&
                    state.currentPath.isDescendantOf(viewModel.internalRoot) -> viewModel.internalRoot
                else -> state.currentPath
            }
        }
        io.github.hatake716.dango.ui.browser.components.ColumnView(
            basePath = base,
            currentPath = state.currentPath,
            selection = state.selection,
            refreshTick = state.refreshTick,
            loadChildren = viewModel::loadChildren,
            onNavigate = { viewModel.navigateTo(it) },
            onTapFile = viewModel::onEntryTap,
            onDoubleTapFile = viewModel::onEntryDoubleTap,
        )
        return
    }
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
                paneState.viewMode == ViewMode.GALLERY -> {
                    io.github.hatake716.dango.ui.browser.components.GalleryView(
                        entries = paneState.entries,
                        selection = paneState.selection,
                        // ストリップのタップは常に「表示切替」（シングルタップで開く設定に左右されない）
                        onSelect = viewModel::selectOnly,
                        onOpen = viewModel::onEntryDoubleTap,
                        onLongPress = viewModel::onEntryLongPress,
                    )
                }
                paneState.viewMode == ViewMode.LIST -> {
                    FileListView(
                        rows = paneState.listRows,
                        selection = paneState.selection,
                        sort = paneState.sort,
                        renamingKey = paneState.renamingKey,
                        pastedKeys = paneState.pastedKeys,
                        tagsByKey = paneState.tagsByKey,
                        hooks = hooks,
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
                        tagsByKey = paneState.tagsByKey,
                        hooks = hooks,
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
