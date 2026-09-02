package io.github.hatake716.dango.ui.browser

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hatake716.dango.DangoApp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.archive.ArchiveError
import io.github.hatake716.dango.data.archive.ArchiveFormat
import io.github.hatake716.dango.data.archive.ArchivePasswordException
import io.github.hatake716.dango.data.archive.ArchivePaths
import io.github.hatake716.dango.data.archive.ArchiveUnsupportedException
import io.github.hatake716.dango.data.archive.CompressFormat
import io.github.hatake716.dango.data.db.ConnectionEntity
import io.github.hatake716.dango.data.db.EntryTagEntity
import io.github.hatake716.dango.data.fs.NameUtils
import io.github.hatake716.dango.data.fs.local.LocalFileSystemProvider
import io.github.hatake716.dango.data.fs.local.LocalLocations
import io.github.hatake716.dango.data.net.HostKeyChangedException
import io.github.hatake716.dango.data.net.NetPaths
import io.github.hatake716.dango.data.net.NetProtocol
import io.github.hatake716.dango.data.net.NetworkAuthException
import io.github.hatake716.dango.data.net.NetworkTester
import io.github.hatake716.dango.domain.model.ClipboardMode
import io.github.hatake716.dango.domain.model.ClipboardState
import io.github.hatake716.dango.domain.model.EntryKind
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.domain.model.FsPath
import io.github.hatake716.dango.domain.model.SortKey
import io.github.hatake716.dango.domain.model.SortSpec
import io.github.hatake716.dango.domain.model.ThemeMode
import io.github.hatake716.dango.domain.model.ViewMode
import io.github.hatake716.dango.service.TransferService
import io.github.hatake716.dango.ui.util.kindLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** コンテンツ切替アニメーションの向き（SPEC §5: 開くと戻るで逆再生） */
enum class NavDirection { FORWARD, BACKWARD, JUMP }

/** サイドバー項目（SPEC §4.3。ユーザー追加・タグは M5 以降） */
data class SidebarItem(
    val id: String,
    @param:StringRes val labelRes: Int,
    val path: FsPath,
)

/** リスト表示のツリー行（SPEC §4.4: ▸ 展開。depth はインデント段数） */
data class TreeRow(
    val entry: FsEntry,
    val depth: Int,
    val expanded: Boolean,
)

/** 完全削除の確認待ち（SPEC §6.3: 完全削除は確認ダイアログ必須） */
data class PendingDelete(
    val entries: List<FsEntry>,
    val emptyAll: Boolean = false,
)

sealed interface BrowserEvent {
    data class Message(@param:StringRes val res: Int) : BrowserEvent
    /** ゴミ箱へ移動した通知。スナックバーの「元に戻す」用に id を持つ */
    data class TrashDone(val count: Int, val undoIds: List<Long>) : BrowserEvent
    data class Share(val entries: List<FsEntry>) : BrowserEvent
    data class OpenWith(val entry: FsEntry) : BrowserEvent
}

data class BrowserUiState(
    val currentPath: FsPath,
    val entries: List<FsEntry> = emptyList(),
    val listRows: List<TreeRow> = emptyList(),
    val loading: Boolean = true,
    @param:StringRes val errorRes: Int? = null,
    val selection: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val viewMode: ViewMode = ViewMode.ICON,
    val sort: SortSpec = SortSpec(),
    val showHidden: Boolean = false,
    val iconSizeDp: Int = 76,
    /** リスト表示の列幅（SPEC §4.4 列カスタマイズ。名前列は残り幅） */
    val listDateWidthDp: Int = 128,
    val listSizeWidthDp: Int = 76,
    val listKindWidthDp: Int = 112,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val navDirection: NavDirection = NavDirection.JUMP,
    val freeSpaceBytes: Long? = null,
    /** インライン編集中の entry key（新規作成直後・リネーム） */
    val renamingKey: String? = null,
    val clipboard: ClipboardState? = null,
    /** 貼り付け直後の強調表示対象（SPEC §5: コピー完了パルス） */
    val pastedKeys: Set<String> = emptySet(),
    val isTrash: Boolean = false,
    val quickLookFiles: List<FsEntry> = emptyList(),
    val quickLookIndex: Int? = null,
    val infoTarget: FsEntry? = null,
    val pendingDelete: PendingDelete? = null,
    /** 複数選択リネームのダイアログ表示中 */
    val pendingBatchRename: Boolean = false,
    /** アーカイブ内ブラウズ中（読み取り専用。SPEC §6.4） */
    val isArchive: Boolean = false,
    /** 圧縮オプションダイアログ表示中 */
    val showCompressDialog: Boolean = false,
    /** 「オプションを指定して展開」の対象 */
    val extractOptionsFor: FsEntry? = null,
    /** パスワード入力待ちのアーカイブ表示名（null なら非表示） */
    val archivePasswordAsk: String? = null,
    /** ネットワークドライブ閲覧中（SPEC §7） */
    val isNetwork: Boolean = false,
    /** エントリ key → 付与タグ色の集合（SPEC §6.3 タグ） */
    val tagsByKey: Map<String, Set<String>> = emptyMap(),
    /** 検索モード（SPEC §6.7: インクリメンタル検索） */
    val searchActive: Boolean = false,
    val searchQuery: String = "",
    /** false=現在フォルダ以下 / true=内部ストレージ全体 */
    val searchGlobal: Boolean = false,
    val searching: Boolean = false,
    /** 設定画面（SPEC §10） */
    val showSettings: Boolean = false,
    /** 接続追加/編集ダイアログ（SPEC §7.2）。id=0 は新規 */
    val editingConnection: ConnectionEntity? = null,
    /** ネットワークパスワード入力（接続ID と表示名） */
    val netPasswordAsk: Pair<Long, String>? = null,
    /** ファイル操作後にカラム表示の列を再読込させるためのカウンタ */
    val refreshTick: Int = 0,
)

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as DangoApp).container
    private val registry = container.providerRegistry
    private val settingsRepo = container.settingsRepository
    private val trashManager = container.trashManager
    private val transferManager = container.transferManager
    private val archiveManager = container.archiveManager
    private val connectionDao = container.database.connectionDao()
    private val tagDao = container.database.entryTagDao()
    private val credentialStore = container.credentialStore
    private val netPreviewCache = container.netPreviewCache
    val infoLoader = container.infoLoader
    val textFileStore = container.textFileStore

    private fun providerFor(path: FsPath) = registry.forPath(path)

    /** アーカイブごとの入力済みパスワードと文字コード指定（セッション内のみ保持） */
    private val archivePasswords = mutableMapOf<String, String>()
    private val archiveEncodings = mutableMapOf<String, String>()

    /** パスワード入力後に再実行する処理 */
    private var pendingArchiveOp: (() -> Unit)? = null

    val internalRoot: FsPath = LocalLocations.internalStorage()

    val sidebarFavorites: List<SidebarItem> = listOf(
        SidebarItem("downloads", R.string.fav_downloads, LocalLocations.downloads()),
        SidebarItem("documents", R.string.fav_documents, LocalLocations.documents()),
        SidebarItem("pictures", R.string.fav_pictures, LocalLocations.pictures()),
        SidebarItem("movies", R.string.fav_movies, LocalLocations.movies()),
        SidebarItem("music", R.string.fav_music, LocalLocations.music()),
    )

    val sidebarLocations: List<SidebarItem> = listOf(
        SidebarItem("internal", R.string.loc_internal, internalRoot),
        SidebarItem("trash", R.string.loc_trash, TRASH_PATH),
    )

    /** 保存済みネットワーク接続（SPEC §4.3 サイドバー「ネットワーク」） */
    val connections: StateFlow<List<ConnectionEntity>> = connectionDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** タグの7色（SPEC §6.3, §9） */
    val tagColors: List<String> = TAG_COLORS

    // 初期値 null: DataStore の実値が届く前に既定値で UI を確定させない（起動時フラッシュ防止)
    val settings = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _state = MutableStateFlow(BrowserUiState(currentPath = internalRoot))
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    /** 転送・圧縮解凍の進捗と衝突照会（SPEC §8.3。ステータスバーはこれを購読する） */
    val transferProgress = combine(transferManager.progress, archiveManager.progress) { t, a -> t ?: a }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val conflictRequest = transferManager.conflict

    private val anyBusy: Boolean get() = transferManager.isBusy || archiveManager.isBusy

    private val _events = Channel<BrowserEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val backStack = ArrayDeque<FsPath>()
    private val forwardStack = ArrayDeque<FsPath>()
    private var rawEntries: List<FsEntry> = emptyList()
    private var searchJob: Job? = null
    private var pendingNetOp: (() -> Unit)? = null

    /** カラム表示の列ごとの一覧キャッシュ（SPEC §4.4。操作後の refresh でクリア） */
    private val columnCache = mutableMapOf<String, List<FsEntry>>()
    private var loadJob: Job? = null
    private var iconSizePersistJob: Job? = null

    /** リストのツリー展開状態（フォルダ移動でリセット） */
    private val expandedKeys = mutableSetOf<String>()
    private val childrenCache = mutableMapOf<String, List<FsEntry>>()

    init {
        viewModelScope.launch {
            // 自動削除日数は設定に従う（SPEC §6.6, §10）
            val days = settingsRepo.settings.filterNotNull().firstOrNull()?.trashAutoDays ?: 30
            trashManager.purgeExpired(days.toLong())
        }
        viewModelScope.launch {
            settings.filterNotNull().collect { s ->
                val visible = applyView(rawEntries, s.sort, s.showHidden)
                val rows = buildTreeRows(visible, s.sort, s.showHidden)
                // ツリー展開中の子行も「見えている」ので選択の整合判定に含める
                val visibleKeys = rows.mapTo(mutableSetOf()) { it.entry.path.key } +
                    visible.map { it.path.key }
                // ドラッグ・ピンチ由来の値はデバウンス永続化が保留中の間、settings の
                // 古い値で巻き戻さない（別の設定書き込みが窓内に emit してくる場合がある）
                val cur = _state.value
                val colsPending = colWidthsPersistJob?.isActive == true
                val iconPending = iconSizePersistJob?.isActive == true
                _state.value = cur.copy(
                    viewMode = s.viewMode,
                    sort = s.sort,
                    showHidden = s.showHidden,
                    iconSizeDp = if (iconPending) cur.iconSizeDp else s.iconSizeDp,
                    listDateWidthDp = if (colsPending) cur.listDateWidthDp else s.listDateWidthDp,
                    listSizeWidthDp = if (colsPending) cur.listSizeWidthDp else s.listSizeWidthDp,
                    listKindWidthDp = if (colsPending) cur.listKindWidthDp else s.listKindWidthDp,
                    entries = visible,
                    listRows = rows,
                    selection = _state.value.selection intersect visibleKeys,
                )
            }
        }
        navigateTo(internalRoot, NavDirection.JUMP, recordHistory = false)
    }

    // --- ナビゲーション（SPEC §6.1: 履歴最大50） ---

    fun navigateTo(path: FsPath, direction: NavDirection = NavDirection.FORWARD, recordHistory: Boolean = true) {
        val current = _state.value.currentPath
        if (recordHistory && current != path) {
            backStack.addLast(current)
            while (backStack.size > HISTORY_LIMIT) backStack.removeFirst()
            forwardStack.clear()
        }
        load(path, direction)
    }

    fun goBack() {
        val prev = backStack.removeLastOrNull() ?: return
        forwardStack.addLast(_state.value.currentPath)
        load(prev, NavDirection.BACKWARD)
    }

    fun goForward() {
        val next = forwardStack.removeLastOrNull() ?: return
        backStack.addLast(_state.value.currentPath)
        load(next, NavDirection.FORWARD)
    }

    fun goUp() {
        val current = _state.value.currentPath
        // アーカイブのルートから上へ出るときは実ファイルの親フォルダへ戻る
        if (ArchivePaths.isArchivePath(current) && current.segments.size == 1) {
            val parentDir = File(ArchivePaths.archiveFile(current)).parent ?: return
            navigateTo(LocalFileSystemProvider.fromAbsolutePath(parentDir), NavDirection.BACKWARD)
            return
        }
        // ネットワーク接続のルート・タグ検索から上へ出るときは内部ストレージへ
        if ((NetPaths.isNetwork(current) && current.segments.size <= 1) ||
            current.scheme == TAG_SCHEME
        ) {
            navigateTo(internalRoot, NavDirection.BACKWARD)
            return
        }
        val parent = current.parent ?: return
        navigateTo(parent, NavDirection.BACKWARD)
    }

    fun reload() {
        load(_state.value.currentPath, NavDirection.JUMP)
    }

    /** 表示を静かに更新する（アニメーションや選択リセットを伴わない） */
    private fun refresh(thenRenameKey: String? = null) {
        // 検索モード中はフォルダ一覧で検索結果を上書きせず、検索を再実行する
        if (_state.value.searchActive) {
            columnCache.clear()
            setSearchQuery(_state.value.searchQuery)
            return
        }
        val path = _state.value.currentPath
        viewModelScope.launch {
            val result = runCatching { listPath(path) }
            // 完了までにユーザーが別フォルダへ移動していたら、この結果は捨てる
            if (_state.value.currentPath != path) return@launch
            result.onSuccess { list ->
                rawEntries = list
                columnCache.clear()
                // 展開中のツリー子要素も再取得して古い表示を残さない
                for (key in expandedKeys.toList()) {
                    val entry = (list + childrenCache.values.flatten()).find { it.path.key == key }
                    if (entry != null) {
                        childrenCache[key] =
                            runCatching { listPath(entry.path) }.getOrDefault(emptyList())
                    } else {
                        collapseRecursively(key)
                    }
                }
                val s = _state.value
                val visible = applyView(list, s.sort, s.showHidden)
                val visibleKeys = visible.mapTo(mutableSetOf()) { it.path.key }
                _state.value = s.copy(
                    entries = visible,
                    listRows = buildTreeRows(visible, s.sort, s.showHidden),
                    tagsByKey = loadTagsFor(visible),
                    selection = if (thenRenameKey != null) {
                        setOf(thenRenameKey)
                    } else {
                        s.selection intersect visibleKeys
                    },
                    renamingKey = thenRenameKey,
                    loading = false,
                    errorRes = null,
                    refreshTick = s.refreshTick + 1,
                )
            }
        }
    }

    private suspend fun listPath(path: FsPath): List<FsEntry> = when (path.scheme) {
        TRASH_SCHEME -> trashManager.list()
        ArchivePaths.SCHEME -> listArchive(path)
        TAG_SCHEME -> listTag(path)
        else -> providerFor(path).list(path).toList()
    }

    /** タグ検索（SPEC §6.7: タグをタップでタグ検索）。実体が消えた行は掃除する */
    private suspend fun listTag(path: FsPath): List<FsEntry> {
        val tag = path.segments.firstOrNull() ?: return emptyList()
        val result = mutableListOf<FsEntry>()
        for (p in tagDao.pathsWithTag(tag)) {
            val entry = runCatching {
                providerFor(internalRoot).stat(LocalFileSystemProvider.fromAbsolutePath(p))
            }.getOrNull()
            if (entry == null) {
                tagDao.removeAll(p)
            } else {
                result += entry
            }
        }
        return result
    }

    /** 現在の一覧に対するタグを読み込む（ローカルのみ。SQLite の IN 上限を避けてチャンク） */
    private suspend fun loadTagsFor(entries: List<FsEntry>): Map<String, Set<String>> {
        val locals = entries.filter { it.path.scheme == "file" }
        if (locals.isEmpty()) return emptyMap()
        val byPath = locals.map { it.path.displayPath() }
            .chunked(900)
            .flatMap { tagDao.forPaths(it) }
            .groupBy({ it.path }, { it.tag })
        return locals.mapNotNull { e ->
            byPath[e.path.displayPath()]?.let { e.path.key to it.toSet() }
        }.toMap()
    }

    /** アーカイブ内を仮想フォルダとして一覧する（SPEC §6.4, §8.1 data/fs/archive） */
    private suspend fun listArchive(path: FsPath): List<FsEntry> {
        val archiveAbs = ArchivePaths.archiveFile(path)
        val inner = ArchivePaths.inner(path)
        val index = archiveManager.index(
            File(archiveAbs),
            archivePasswords[archiveAbs],
            archiveEncodings[archiveAbs],
        )
        return index.childrenOf(inner).map { meta ->
            val ext = meta.segments.last().substringAfterLast('.', "").lowercase()
            FsEntry(
                path = FsPath(ArchivePaths.SCHEME, listOf(archiveAbs) + meta.segments),
                name = meta.segments.last(),
                isDir = meta.isDir,
                size = if (meta.isDir) -1 else meta.size,
                lastModified = meta.mtime,
                isHidden = meta.segments.last().startsWith("."),
                kind = if (meta.isDir) EntryKind.FOLDER else LocalFileSystemProvider.kindOfExtension(ext),
                previewUri = null,
                fileUri = null,
            )
        }
    }

    private fun load(path: FsPath, direction: NavDirection) {
        loadJob?.cancel()
        expandedKeys.clear()
        childrenCache.clear()
        _state.value = _state.value.copy(
            currentPath = path,
            loading = true,
            errorRes = null,
            selection = emptySet(),
            selectionMode = false,
            renamingKey = null,
            navDirection = direction,
            isTrash = path.scheme == TRASH_SCHEME,
            isArchive = path.scheme == ArchivePaths.SCHEME,
            isNetwork = NetPaths.isNetwork(path),
            searchActive = false,
            searchQuery = "",
            canGoBack = backStack.isNotEmpty(),
            canGoForward = forwardStack.isNotEmpty(),
            quickLookIndex = null,
        )
        searchJob?.cancel()
        loadJob = viewModelScope.launch {
            val result = runCatching { listPath(path) }
            // アーカイブがパスワード付きなら入力を求め、成功後に再読込する
            (result.exceptionOrNull() as? ArchivePasswordException)?.let {
                askArchivePassword(
                    displayName = File(ArchivePaths.archiveFile(path)).name,
                    targetAbs = ArchivePaths.archiveFile(path),
                    upOnCancel = true,
                ) {
                    load(path, NavDirection.JUMP)
                }
                return@launch
            }
            // ネットワーク接続のパスワード未保存・認証失敗は入力を求める（SPEC §7.2）
            (result.exceptionOrNull() as? NetworkAuthException)?.let {
                val connId = NetPaths.connectionId(path)
                val name = connectionDao.byId(connId)?.name ?: path.name
                _state.value = _state.value.copy(
                    loading = false,
                    netPasswordAsk = connId to name,
                )
                pendingNetOp = { load(path, NavDirection.JUMP) }
                return@launch
            }
            (result.exceptionOrNull() as? HostKeyChangedException)?.let {
                _state.value = _state.value.copy(loading = false, errorRes = R.string.hostkey_changed)
                return@launch
            }
            val free = if (path.scheme == "file") {
                providerFor(path).freeSpace(path)
            } else {
                providerFor(internalRoot).freeSpace(internalRoot)
            }
            result.fold(
                onSuccess = { list ->
                    rawEntries = list
                    columnCache.clear()
                    val s = _state.value
                    val visible = applyView(list, s.sort, s.showHidden)
                    val rows = buildTreeRows(visible, s.sort, s.showHidden)
                    val tags = loadTagsFor(visible)
                    // loadTagsFor の suspend 中に他コルーチン（設定反映など）が state を
                    // 更新していることがあるため、書き込みは古いスナップショット s ではなく
                    // 最新値からの copy で行う（起動直後に viewMode が既定値へ巻き戻る等の
                    // lost update 防止）
                    _state.value = _state.value.let { cur ->
                        cur.copy(
                            entries = visible,
                            listRows = rows,
                            tagsByKey = tags,
                            loading = false,
                            freeSpaceBytes = free,
                            refreshTick = cur.refreshTick + 1,
                        )
                    }
                },
                onFailure = {
                    rawEntries = emptyList()
                    _state.value = _state.value.copy(
                        entries = emptyList(),
                        listRows = emptyList(),
                        loading = false,
                        errorRes = R.string.error_cannot_open,
                        freeSpaceBytes = free,
                    )
                },
            )
        }
    }

    // --- 選択と開く（SPEC §6.1, §6.2） ---

    fun onEntryTap(entry: FsEntry, ctrl: Boolean = false, shift: Boolean = false) {
        val key = entry.path.key
        val s = _state.value
        if (s.renamingKey != null) return
        // 修飾キー付きクリック（マウス/キーボード操作。SPEC §6.2 の複数選択）。
        // アーカイブ内は読み取り専用のため他の複数選択入口と同様に受け付けない
        if (!s.selectionMode && (ctrl || shift) && !s.isArchive) {
            when {
                shift && tapAnchorKey == null -> {
                    // アンカー未確定の Shift+クリックは追加選択として扱う
                    //（トグルだと選択済みを外してしまい「拡張」の意図に反する）
                    _state.value = s.copy(selection = s.selection + key)
                    tapAnchorKey = key
                }
                shift -> {
                    // アンカーからの範囲選択。アンカーは維持し、連続 Shift+クリックで伸縮できる
                    val pool = visibleKeyOrder(s)
                    val i0 = pool.indexOf(tapAnchorKey)
                    val i1 = pool.indexOf(key)
                    if (i0 >= 0 && i1 >= 0) {
                        val range = pool.subList(minOf(i0, i1), maxOf(i0, i1) + 1).toSet()
                        _state.value = s.copy(
                            selection = if (ctrl) s.selection + range else range,
                        )
                    } else {
                        _state.value = s.copy(selection = setOf(key))
                        tapAnchorKey = key
                    }
                }
                else -> {
                    toggleSelect(key)
                    tapAnchorKey = key
                }
            }
            return
        }
        if (s.selectionMode) {
            toggleSelect(key)
            tapAnchorKey = key
            return
        }
        // シングルタップで開く（SPEC §6.1, §10）
        if (settings.value?.singleTapOpen == true) {
            _state.value = s.copy(selection = setOf(key))
            tapAnchorKey = key
            open(entry)
            return
        }
        if (key in s.selection) {
            open(entry)
        } else {
            _state.value = s.copy(selection = setOf(key))
            tapAnchorKey = key
        }
    }

    /** Shift+クリック範囲選択の起点（直近に単独/トグル選択したキー） */
    private var tapAnchorKey: String? = null

    private fun visibleKeyOrder(s: BrowserUiState): List<String> =
        if (s.viewMode == ViewMode.LIST) {
            s.listRows.map { it.entry.path.key }
        } else {
            s.entries.map { it.path.key }
        }

    /** ラバーバンド選択（SPEC §6.2）: 選択を置き換える。選択モードには入らない（Finder 同様） */
    fun setSelectionByMarquee(keys: Set<String>) {
        val s = _state.value
        if (s.renamingKey != null || s.isArchive) return
        if (s.selection != keys) {
            _state.value = s.copy(selection = keys)
        }
        // 直後の Shift+クリックが自然につながるよう、範囲選択の起点も更新する
        tapAnchorKey = visibleKeyOrder(s).firstOrNull { it in keys } ?: tapAnchorKey
    }

    fun onEntryDoubleTap(entry: FsEntry) {
        if (_state.value.selectionMode) return
        open(entry)
    }

    fun onEntryLongPress(entry: FsEntry) {
        val s = _state.value
        if (s.renamingKey != null || s.isArchive) return
        val key = entry.path.key
        when {
            // 選択モード外でもマーキー/Ctrl+クリックによる複数選択があり得る。
            // 押されたキーが選択済みならグループを維持したまま選択モードに入る
            //（ドラッグ開始の前段。単独置換すると他の選択対象が抜け落ちてしまう）
            !s.selectionMode ->
                _state.value = if (key in s.selection) {
                    s.copy(selectionMode = true)
                } else {
                    s.copy(selectionMode = true, selection = setOf(key))
                }
            key !in s.selection ->
                _state.value = s.copy(selection = s.selection + key)
            // 選択済みへの長押しはドラッグ開始の前段なので選択を維持する
            // （解除はタップで行える。長押しで外れるとドラッグ対象が抜け落ちてしまう）
        }
    }

    /**
     * ドラッグ源を持たないビュー（ギャラリーのストリップ等）用の長押し。
     * タップが選択トグルに割り当てられていないビューでは、これが唯一の
     * 個別解除手段なので従来どおりトグルする
     */
    fun onEntryLongPressToggle(entry: FsEntry) {
        val s = _state.value
        if (s.renamingKey != null || s.isArchive) return
        if (!s.selectionMode) {
            _state.value = s.copy(selectionMode = true, selection = setOf(entry.path.key))
        } else {
            toggleSelect(entry.path.key)
        }
    }

    private fun toggleSelect(key: String) {
        val selection = _state.value.selection
        _state.value = _state.value.copy(
            selection = if (key in selection) selection - key else selection + key,
        )
    }

    fun exitSelectionMode() {
        _state.value = _state.value.copy(selectionMode = false, selection = emptySet())
    }

    fun startSelectionMode() {
        if (_state.value.isArchive) return // アーカイブ内は読み取り専用（SPEC §6.4）
        _state.value = _state.value.copy(selectionMode = true)
    }

    fun selectAll() {
        if (_state.value.isArchive) return
        _state.value = _state.value.copy(
            selectionMode = true,
            selection = allVisibleKeys(),
        )
    }

    fun invertSelection() {
        if (_state.value.isArchive) return
        val all = allVisibleKeys()
        _state.value = _state.value.copy(
            selectionMode = true,
            selection = all - _state.value.selection,
        )
    }

    private fun allVisibleKeys(): Set<String> {
        val s = _state.value
        return if (s.viewMode == ViewMode.LIST) {
            s.listRows.mapTo(mutableSetOf()) { it.entry.path.key }
        } else {
            s.entries.mapTo(mutableSetOf()) { it.path.key }
        }
    }

    fun selectedEntries(): List<FsEntry> {
        val s = _state.value
        val pool = if (s.viewMode == ViewMode.LIST) s.listRows.map { it.entry } else s.entries
        return pool.filter { it.path.key in s.selection }
    }

    private fun open(entry: FsEntry) {
        when {
            entry.isRestricted -> _events.trySend(BrowserEvent.Message(R.string.restricted_folder))
            entry.isDir && !_state.value.isTrash -> navigateTo(entry.path, NavDirection.FORWARD)
            entry.isDir -> _events.trySend(BrowserEvent.Message(R.string.trash_folder_preview))
            ArchivePaths.isArchivePath(entry.path) -> previewArchiveEntry(entry)
            // ネットワーク上のファイルは一時ダウンロードしてから Quick Look（SPEC §7.3）
            NetPaths.isNetwork(entry.path) -> previewNetworkEntry(entry)
            // アーカイブを開く＝既定は「同名フォルダに展開」（SPEC §6.4）
            entry.kind == EntryKind.ARCHIVE && !_state.value.isTrash ->
                extractArchive(entry, wrapInFolder = true)
            else -> openQuickLook(entry)
        }
    }

    /** ネットワークファイルをキャッシュへ落として Quick Look で開く（SPEC §7.3） */
    private fun previewNetworkEntry(entry: FsEntry) {
        viewModelScope.launch {
            _events.trySend(BrowserEvent.Message(R.string.net_downloading))
            runCatching { netPreviewCache.fetch(entry, registry) }
                .fold(
                    onSuccess = { cached ->
                        val real = runCatching {
                            providerFor(internalRoot)
                                .stat(LocalFileSystemProvider.fromAbsolutePath(cached.absolutePath))
                        }.getOrNull() ?: return@fold
                        _state.value = _state.value.copy(
                            quickLookFiles = listOf(real.copy(name = entry.name)),
                            quickLookIndex = 0,
                        )
                    },
                    onFailure = { e ->
                        if (e is NetworkAuthException) {
                            askNetPassword(entry.path) { previewNetworkEntry(entry) }
                        } else {
                            _events.trySend(BrowserEvent.Message(R.string.net_error))
                        }
                    },
                )
        }
    }

    // --- 圧縮・解凍（SPEC §6.4） ---

    fun extractArchive(
        entry: FsEntry,
        wrapInFolder: Boolean,
        encodingOverride: String? = null,
        explicitEncoding: Boolean = false,
    ) {
        val s = _state.value
        if (s.isNetwork) {
            _events.trySend(BrowserEvent.Message(R.string.net_unsupported_op))
            return
        }
        if (s.isTrash || s.isArchive || anyBusy) return
        val archive = File(entry.path.displayPath())
        lastArchiveTarget = archive.absolutePath
        if (explicitEncoding) {
            // 「自動」を選び直したときは過去の明示指定を消す
            if (encodingOverride == null) {
                archiveEncodings.remove(archive.absolutePath)
            } else {
                archiveEncodings[archive.absolutePath] = encodingOverride
            }
        }
        val op = {
            TransferService.start(getApplication())
            archiveManager.extractAll(
                archive = archive,
                destRoot = File(s.currentPath.displayPath()),
                wrapInFolder = wrapInFolder,
                password = archivePasswords[archive.absolutePath],
                encodingOverride = archiveEncodings[archive.absolutePath],
            ) { result ->
                viewModelScope.launch { onArchiveOpFinished(result, archive.name) }
            }
        }
        pendingArchiveOp = op
        op()
    }

    fun browseArchive(entry: FsEntry) {
        navigateTo(ArchivePaths.root(entry.path.displayPath()), NavDirection.FORWARD)
    }

    fun showExtractOptions(entry: FsEntry) {
        _state.value = _state.value.copy(extractOptionsFor = entry)
    }

    fun dismissExtractOptions() {
        _state.value = _state.value.copy(extractOptionsFor = null)
    }

    fun requestCompress() {
        if (_state.value.isNetwork) {
            _events.trySend(BrowserEvent.Message(R.string.net_unsupported_op))
            return
        }
        if (selectedEntries().isEmpty() || _state.value.isTrash || _state.value.isArchive) return
        _state.value = _state.value.copy(showCompressDialog = true)
    }

    fun dismissCompress() {
        _state.value = _state.value.copy(showCompressDialog = false)
    }

    fun compressSelected(
        format: CompressFormat,
        level: Int,
        password: String?,
        deleteSource: Boolean,
    ) {
        val s = _state.value
        val sources = selectedEntries().filter { !it.isRestricted }
        _state.value = s.copy(showCompressDialog = false)
        if (sources.isEmpty() || anyBusy) return
        TransferService.start(getApplication())
        archiveManager.compress(
            sources = sources.map { File(it.path.displayPath()) },
            destDir = File(s.currentPath.displayPath()),
            format = format,
            level = level,
            password = password?.takeIf { it.isNotEmpty() && format == CompressFormat.ZIP },
            multiBaseName = getApplication<Application>().getString(R.string.archive_default_name),
        ) { result ->
            viewModelScope.launch {
                exitSelectionMode()
                // 「圧縮後に元を削除」は完全削除ではなくゴミ箱へ（SPEC §6.6 の削除モデルに合わせる）
                if (deleteSource && !result.cancelled && result.error == null) {
                    runCatching { trashManager.moveToTrash(sources) }
                        .onSuccess { ids -> _events.trySend(BrowserEvent.TrashDone(ids.size, ids)) }
                }
                onArchiveOpFinished(result, sources.first().name)
            }
        }
    }

    private suspend fun onArchiveOpFinished(
        result: io.github.hatake716.dango.data.archive.ArchiveOpResult,
        archiveName: String,
    ) {
        refresh()
        when {
            result.cancelled -> _events.trySend(BrowserEvent.Message(R.string.transfer_cancelled))
            result.error == ArchiveError.PASSWORD -> {
                // askArchivePassword が pendingArchiveOp を上書きするため、先にキャプチャする
                // （そのまま渡すと自己参照ラムダになり無限再帰でクラッシュする）
                val op = pendingArchiveOp
                askArchivePassword(archiveName, lastArchiveTarget) { op?.invoke() }
            }
            result.error == ArchiveError.NO_SPACE ->
                _events.trySend(BrowserEvent.Message(R.string.extract_no_space))
            result.error == ArchiveError.UNSUPPORTED ->
                _events.trySend(BrowserEvent.Message(R.string.archive_unsupported))
            result.error == ArchiveError.FAILED ->
                _events.trySend(BrowserEvent.Message(R.string.op_failed))
            else -> {
                val keys = result.createdNames
                    .mapTo(mutableSetOf()) { _state.value.currentPath.child(it).key }
                _state.value = _state.value.copy(pastedKeys = keys)
                _events.trySend(BrowserEvent.Message(R.string.transfer_done))
                delay(600)
                _state.value = _state.value.copy(pastedKeys = emptySet())
            }
        }
    }

    /** パスワード入力の紐付け先（ask 時点で確定させ、submit 時の画面状態に依存しない） */
    private var pendingArchiveTargetAbs: String? = null
    private var pendingUpOnCancel: Boolean = false

    private fun askArchivePassword(
        displayName: String,
        targetAbs: String?,
        upOnCancel: Boolean = false,
        retry: () -> Unit,
    ) {
        pendingArchiveOp = retry
        pendingArchiveTargetAbs = targetAbs
        pendingUpOnCancel = upOnCancel
        // 誤パスワードが保存されている可能性があるので破棄してから聞き直す
        targetAbs?.let { archivePasswords.remove(it) }
        _state.value = _state.value.copy(archivePasswordAsk = displayName)
    }

    fun submitArchivePassword(password: String) {
        _state.value = _state.value.copy(archivePasswordAsk = null)
        pendingArchiveTargetAbs?.let { archivePasswords[it] = password }
        // 再度 PASSWORD エラーになったときのため pendingArchiveOp は保持したまま実行する
        pendingArchiveOp?.invoke()
    }

    fun cancelArchivePassword() {
        _state.value = _state.value.copy(archivePasswordAsk = null)
        pendingArchiveOp = null
        // アーカイブブラウズの一覧取得に対する拒否のときだけ1つ上へ戻す
        if (pendingUpOnCancel && _state.value.isArchive) goUp()
        pendingUpOnCancel = false
    }

    private var lastArchiveTarget: String? = null

    /** アーカイブ内のファイルをキャッシュへ展開して Quick Look で開く（SPEC §6.4） */
    private fun previewArchiveEntry(entry: FsEntry) {
        val archiveAbs = ArchivePaths.archiveFile(entry.path)
        lastArchiveTarget = archiveAbs
        viewModelScope.launch {
            val result = runCatching {
                archiveManager.extractEntryToCache(
                    File(archiveAbs),
                    ArchivePaths.inner(entry.path),
                    archivePasswords[archiveAbs],
                    archiveEncodings[archiveAbs],
                )
            }
            result.fold(
                onSuccess = { cached ->
                    val real = runCatching { providerFor(internalRoot).stat(LocalFileSystemProvider.fromAbsolutePath(cached.absolutePath)) }
                        .getOrNull() ?: return@fold
                    _state.value = _state.value.copy(
                        quickLookFiles = listOf(real.copy(name = entry.name)),
                        quickLookIndex = 0,
                    )
                },
                onFailure = { e ->
                    when (e) {
                        is ArchivePasswordException ->
                            askArchivePassword(File(archiveAbs).name, archiveAbs) {
                                previewArchiveEntry(entry)
                            }
                        is ArchiveUnsupportedException ->
                            _events.trySend(BrowserEvent.Message(R.string.archive_unsupported))
                        else -> _events.trySend(BrowserEvent.Message(R.string.op_failed))
                    }
                },
            )
        }
    }

    /** Quick Look のアーカイブページ用にエントリ一覧を返す（SPEC §6.5: エントリ一覧ツリー） */
    suspend fun archiveIndexFor(entry: FsEntry): io.github.hatake716.dango.data.archive.ArchiveIndex {
        val abs = entry.path.displayPath()
        return archiveManager.index(File(abs), archivePasswords[abs], archiveEncodings[abs])
    }

    /** アーカイブ内のファイルをアーカイブと同じフォルダへ書き出す */
    fun exportArchiveEntry(entry: FsEntry) {
        val archiveAbs = ArchivePaths.archiveFile(entry.path)
        lastArchiveTarget = archiveAbs
        val destDir = File(archiveAbs).parentFile ?: return
        viewModelScope.launch {
            runCatching {
                archiveManager.extractEntryTo(
                    File(archiveAbs),
                    ArchivePaths.inner(entry.path),
                    destDir,
                    archivePasswords[archiveAbs],
                    archiveEncodings[archiveAbs],
                )
            }.fold(
                onSuccess = { name ->
                    _events.trySend(BrowserEvent.Message(R.string.archive_exported))
                },
                onFailure = { e ->
                    if (e is ArchivePasswordException) {
                        askArchivePassword(File(archiveAbs).name, archiveAbs) {
                            exportArchiveEntry(entry)
                        }
                    } else {
                        _events.trySend(BrowserEvent.Message(R.string.op_failed))
                    }
                },
            )
        }
    }

    // --- Quick Look（SPEC §6.5） ---

    fun openQuickLook(entry: FsEntry) {
        val s = _state.value
        val pool = if (s.viewMode == ViewMode.LIST) s.listRows.map { it.entry } else s.entries
        val files = pool.filter { !it.isDir && !it.isRestricted }
        val index = files.indexOfFirst { it.path.key == entry.path.key }
        if (index >= 0) {
            _state.value = s.copy(quickLookFiles = files, quickLookIndex = index)
        } else if (!entry.isDir && !entry.isRestricted) {
            // 現在の一覧に居ないエントリ（カラム表示の途中列など）は単体で開く
            _state.value = s.copy(quickLookFiles = listOf(entry), quickLookIndex = 0)
        }
    }

    /** 選択のみ（開かない）。ギャラリーのフィルムストリップなど表示切替用 */
    fun selectOnly(entry: FsEntry) {
        _state.value = _state.value.copy(selection = setOf(entry.path.key))
    }

    fun setQuickLookIndex(index: Int) {
        val s = _state.value
        if (index in s.quickLookFiles.indices) {
            _state.value = s.copy(quickLookIndex = index)
        }
    }

    fun closeQuickLook() {
        _state.value = _state.value.copy(quickLookIndex = null, quickLookFiles = emptyList())
    }

    // --- ファイル操作（SPEC §6.3） ---

    fun createFolder() {
        if (_state.value.isTrash || _state.value.isArchive) return
        viewModelScope.launch {
            val name = NameUtils.uniqueName(
                currentNames(),
                getApplication<Application>().getString(R.string.untitled_folder),
                isDir = true,
            )
            val path = _state.value.currentPath.child(name)
            runCatching { providerFor(path).mkdir(path) }
                .onSuccess { refresh(thenRenameKey = path.key) }
                .onFailure { _events.trySend(BrowserEvent.Message(R.string.op_failed)) }
        }
    }

    fun createTextFile(extension: String) {
        if (_state.value.isTrash || _state.value.isArchive) return
        viewModelScope.launch {
            val base = getApplication<Application>().getString(R.string.untitled_file)
            val name = NameUtils.uniqueName(currentNames(), "$base.$extension", isDir = false)
            val path = _state.value.currentPath.child(name)
            runCatching {
                withContext(Dispatchers.IO) { providerFor(path).openWrite(path, append = false).close() }
            }
                .onSuccess { refresh(thenRenameKey = path.key) }
                .onFailure { _events.trySend(BrowserEvent.Message(R.string.op_failed)) }
        }
    }

    fun startRename() {
        val selected = selectedEntries()
        if (_state.value.isTrash || _state.value.isArchive) return
        when {
            selected.size == 1 -> _state.value = _state.value.copy(
                renamingKey = selected.first().path.key,
                selectionMode = false,
            )
            selected.size > 1 -> _state.value = _state.value.copy(
                pendingBatchRename = true,
            )
            else -> Unit
        }
    }

    fun commitRename(key: String, newName: String) {
        // Back でのキャンセル後にフォーカス喪失 commit が飛んでくるケースを無視する
        if (_state.value.renamingKey != key) return
        // 検索結果・タグ検索の行は rawEntries に居ないため state.entries も探索対象に含める
        val entry = (rawEntries + childrenCache.values.flatten() + _state.value.entries)
            .find { it.path.key == key }
        _state.value = _state.value.copy(renamingKey = null)
        if (entry == null || newName == entry.name) return
        if (NameUtils.validate(newName) != null) {
            _events.trySend(BrowserEvent.Message(R.string.rename_invalid))
            return
        }
        viewModelScope.launch {
            // ツリー展開行のリネームでは表示中フォルダではなく実際の親フォルダで衝突を見る
            val parent = entry.path.parent ?: return@launch
            val siblings = namesIn(parent) - entry.name
            if (siblings.any { it.equals(newName, ignoreCase = true) }) {
                _events.trySend(BrowserEvent.Message(R.string.rename_exists))
                return@launch
            }
            val newPath = parent.child(newName)
            runCatching { providerFor(entry.path).rename(entry.path, newPath) }
                .onSuccess {
                    // タグを新しいパスへ付け替える（SPEC §6.3: タグは Room 保存のため）
                    if (entry.path.scheme == "file") {
                        runCatching {
                            tagDao.rename(entry.path.displayPath(), newPath.displayPath())
                        }
                    }
                    refresh()
                    _state.value = _state.value.copy(selection = setOf(newPath.key))
                }
                .onFailure { _events.trySend(BrowserEvent.Message(R.string.op_failed)) }
        }
    }

    fun cancelRename() {
        _state.value = _state.value.copy(renamingKey = null)
    }

    /** 一括リネーム（SPEC §6.3: 連番・検索置換・接頭辞/接尾辞） */
    fun applyBatchRename(transform: (index: Int, name: String, isDir: Boolean) -> String) {
        val targets = selectedEntries()
        dismissBatchRename()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            var failed = 0
            targets.forEachIndexed { index, entry ->
                val desired = transform(index, entry.name, entry.isDir)
                if (desired == entry.name || NameUtils.validate(desired) != null) return@forEachIndexed
                // 衝突判定は各エントリの実親フォルダで行う（ツリー展開行対応）
                val parent = entry.path.parent ?: return@forEachIndexed
                val siblings = namesIn(parent) - entry.name
                val unique = NameUtils.uniqueName(siblings, desired, entry.isDir)
                val newPath = parent.child(unique)
                runCatching { providerFor(entry.path).rename(entry.path, newPath) }
                    .onSuccess {
                        if (entry.path.scheme == "file") {
                            runCatching {
                                tagDao.rename(entry.path.displayPath(), newPath.displayPath())
                            }
                        }
                    }
                    .onFailure { failed++ }
            }
            exitSelectionMode()
            refresh()
            if (failed > 0) _events.trySend(BrowserEvent.Message(R.string.op_failed))
        }
    }

    fun dismissBatchRename() {
        _state.value = _state.value.copy(pendingBatchRename = false)
    }

    fun copySelected() {
        setClipboard(ClipboardMode.COPY, R.string.clip_copied)
    }

    fun cutSelected() {
        setClipboard(ClipboardMode.MOVE, R.string.clip_cut)
    }

    private fun setClipboard(mode: ClipboardMode, @StringRes messageRes: Int) {
        val selected = selectedEntries().filter { !it.isRestricted }
        if (selected.isEmpty() || _state.value.isTrash || _state.value.isArchive) return
        _state.value = _state.value.copy(
            clipboard = ClipboardState(selected, mode),
            selectionMode = false,
            selection = emptySet(),
        )
        _events.trySend(BrowserEvent.Message(messageRes))
    }

    fun clearClipboard() {
        _state.value = _state.value.copy(clipboard = null)
    }

    /** クリップボードを destDir へ貼り付ける（既定は現在のフォルダ。右クリック「このフォルダに貼り付け」対応） */
    fun paste(destDir: FsPath = _state.value.currentPath) {
        val s = _state.value
        val clipboard = s.clipboard ?: return
        if (s.isTrash || s.isArchive || anyBusy) return
        // フォルダを自分自身（または子孫）へは貼り付けできない（SPEC §6.3。無限再帰防止）
        if (clipboard.entries.any { e ->
                e.isDir && (destDir == e.path || destDir.isDescendantOf(e.path))
            }
        ) {
            _events.trySend(BrowserEvent.Message(R.string.paste_into_itself))
            return
        }
        TransferService.start(getApplication())
        transferManager.start(
            entries = clipboard.entries,
            destDir = destDir,
            move = clipboard.mode == ClipboardMode.MOVE,
        ) { result ->
            viewModelScope.launch {
                if (clipboard.mode == ClipboardMode.MOVE && !result.cancelled) {
                    _state.value = _state.value.copy(clipboard = null)
                }
                val pasted = result.copiedNames
                    .mapTo(mutableSetOf()) { destDir.child(it).key }
                _state.value = _state.value.copy(pastedKeys = pasted)
                refresh()
                when {
                    result.cancelled -> _events.trySend(BrowserEvent.Message(R.string.transfer_cancelled))
                    result.failed > 0 -> _events.trySend(BrowserEvent.Message(R.string.transfer_partial))
                    else -> _events.trySend(BrowserEvent.Message(R.string.transfer_done))
                }
                delay(600)
                _state.value = _state.value.copy(pastedKeys = emptySet())
            }
        }
    }

    fun duplicateSelected() {
        val selected = selectedEntries().filter { !it.isRestricted }
        val s = _state.value
        if (s.isNetwork) {
            _events.trySend(BrowserEvent.Message(R.string.net_unsupported_op))
            return
        }
        if (selected.isEmpty() || s.isTrash || s.isArchive || anyBusy) return
        TransferService.start(getApplication())
        transferManager.start(
            entries = selected,
            destDir = s.currentPath,
            move = false,
            duplicateInPlace = true,
        ) { result ->
            viewModelScope.launch {
                val pasted = result.copiedNames
                    .mapTo(mutableSetOf()) { _state.value.currentPath.child(it).key }
                _state.value = _state.value.copy(
                    pastedKeys = pasted,
                    selectionMode = false,
                    selection = emptySet(),
                )
                refresh()
                delay(600)
                _state.value = _state.value.copy(pastedKeys = emptySet())
            }
        }
    }

    fun cancelTransfer() {
        transferManager.cancel()
        archiveManager.cancel()
    }

    // --- ドラッグ&ドロップ（SPEC §6.3, §4.3, §4.5） ---

    /** ドラッグされたエントリ群を destDir へ移動する（ローカル同士のみ。SPEC §6.3） */
    fun moveByDrag(keys: Set<String>, destDir: FsPath) {
        val s = _state.value
        if (s.isTrash || s.isArchive || s.isNetwork || anyBusy || destDir.scheme != "file") return
        val pool = (s.entries + s.listRows.map { it.entry }).distinctBy { it.path.key }
        val entries = pool.filter { it.path.key in keys && !it.isRestricted && it.path.scheme == "file" }
        if (entries.isEmpty()) return
        // すべて destDir 直下にある場合は何もしない
        if (entries.all { it.path.parent == destDir }) return
        if (entries.any { e -> e.isDir && (destDir == e.path || destDir.isDescendantOf(e.path)) }) {
            _events.trySend(BrowserEvent.Message(R.string.paste_into_itself))
            return
        }
        exitSelectionMode()
        TransferService.start(getApplication())
        transferManager.start(entries, destDir, move = true) { result ->
            viewModelScope.launch {
                refresh()
                when {
                    result.cancelled -> _events.trySend(BrowserEvent.Message(R.string.transfer_cancelled))
                    result.failed > 0 -> _events.trySend(BrowserEvent.Message(R.string.transfer_partial))
                    else -> _events.trySend(BrowserEvent.Message(R.string.drag_moved))
                }
            }
        }
    }

    /** サイドバーのゴミ箱へのドロップ */
    fun dropKeysToTrash(keys: Set<String>) {
        val s = _state.value
        if (s.isTrash || s.isArchive) return
        val pool = (s.entries + s.listRows.map { it.entry }).distinctBy { it.path.key }
        val entries = pool.filter { it.path.key in keys && !it.isRestricted }
        if (entries.isEmpty()) return
        deleteEntries(entries)
    }

    // --- 削除とゴミ箱（SPEC §6.3, §6.6） ---

    fun deleteSelected() {
        val selected = selectedEntries().filter { !it.isRestricted }
        if (selected.isEmpty() || _state.value.isArchive) return
        // ゴミ箱内は完全削除、ネットワークはゴミ箱非対応のため即時削除を警告（SPEC §6.6）
        if (_state.value.isTrash || _state.value.isNetwork) {
            _state.value = _state.value.copy(pendingDelete = PendingDelete(selected))
            return
        }
        deleteEntries(selected)
    }

    private fun deleteEntries(entries: List<FsEntry>) {
        viewModelScope.launch {
            runCatching { trashManager.moveToTrash(entries) }
                .onSuccess { ids ->
                    exitSelectionMode()
                    refresh()
                    _events.trySend(BrowserEvent.TrashDone(ids.size, ids))
                }
                .onFailure { _events.trySend(BrowserEvent.Message(R.string.op_failed)) }
        }
    }

    fun undoTrash(ids: List<Long>) {
        viewModelScope.launch {
            val restored = trashManager.restore(ids)
            refresh()
            if (restored.size < ids.size) {
                _events.trySend(BrowserEvent.Message(R.string.trash_restore_partial))
            }
        }
    }

    fun requestEmptyTrash() {
        _state.value = _state.value.copy(pendingDelete = PendingDelete(emptyList(), emptyAll = true))
    }

    fun confirmPendingDelete() {
        val pending = _state.value.pendingDelete ?: return
        _state.value = _state.value.copy(pendingDelete = null)
        viewModelScope.launch {
            when {
                pending.emptyAll -> trashManager.emptyTrash()
                // ネットワーク上はゴミ箱を経由せず削除（SPEC §6.6）
                pending.entries.firstOrNull()?.let { NetPaths.isNetwork(it.path) } == true -> {
                    var failed = 0
                    for (entry in pending.entries) {
                        runCatching { providerFor(entry.path).delete(entry.path, recursive = true) }
                            .onFailure { failed++ }
                    }
                    if (failed > 0) _events.trySend(BrowserEvent.Message(R.string.op_failed))
                }
                else -> trashManager.deleteForever(pending.entries.mapNotNull { it.trashId })
            }
            exitSelectionMode()
            refresh()
            _events.trySend(BrowserEvent.Message(R.string.trash_deleted_forever))
        }
    }

    fun dismissPendingDelete() {
        _state.value = _state.value.copy(pendingDelete = null)
    }

    fun restoreSelected() {
        val ids = selectedEntries().mapNotNull { it.trashId }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val restored = trashManager.restore(ids)
            exitSelectionMode()
            refresh()
            _events.trySend(
                BrowserEvent.Message(
                    if (restored.size < ids.size) R.string.trash_restore_partial else R.string.trash_restored,
                ),
            )
        }
    }

    // --- 共有・情報（SPEC §6.3） ---

    fun shareSelected() {
        val files = selectedEntries().filter { !it.isDir }
        if (files.isEmpty()) {
            _events.trySend(BrowserEvent.Message(R.string.share_no_files))
            return
        }
        _events.trySend(BrowserEvent.Share(files))
    }

    fun openWith(entry: FsEntry) {
        _events.trySend(BrowserEvent.OpenWith(entry))
    }

    fun shareEntry(entry: FsEntry) {
        if (entry.isDir) {
            _events.trySend(BrowserEvent.Message(R.string.share_no_files))
        } else {
            _events.trySend(BrowserEvent.Share(listOf(entry)))
        }
    }

    fun showInfo(entry: FsEntry) {
        _state.value = _state.value.copy(infoTarget = entry)
    }

    fun showInfoForSelection() {
        selectedEntries().firstOrNull()?.let { showInfo(it) }
    }

    fun closeInfo() {
        _state.value = _state.value.copy(infoTarget = null)
    }

    // --- リストのツリー展開（SPEC §4.4） ---

    fun toggleExpand(entry: FsEntry) {
        if (!entry.isDir || entry.isRestricted || _state.value.isTrash) return
        val key = entry.path.key
        viewModelScope.launch {
            if (key in expandedKeys) {
                collapseRecursively(key)
            } else {
                expandedKeys += key
                if (key !in childrenCache) {
                    // アーカイブ内の仮想フォルダも展開できるよう listPath を使う
                    childrenCache[key] =
                        runCatching { listPath(entry.path) }.getOrDefault(emptyList())
                }
            }
            val s = _state.value
            _state.value = s.copy(listRows = buildTreeRows(s.entries, s.sort, s.showHidden))
        }
    }

    private fun collapseRecursively(key: String) {
        expandedKeys.remove(key)
        childrenCache[key]?.forEach { child ->
            if (child.isDir) collapseRecursively(child.path.key)
        }
    }

    private suspend fun buildTreeRows(
        top: List<FsEntry>,
        sort: SortSpec,
        showHidden: Boolean,
    ): List<TreeRow> {
        val rows = mutableListOf<TreeRow>()
        suspend fun addLevel(entries: List<FsEntry>, depth: Int) {
            for (entry in entries) {
                val key = entry.path.key
                val expanded = key in expandedKeys
                rows += TreeRow(entry, depth, expanded)
                if (expanded) {
                    val children = childrenCache[key] ?: emptyList()
                    addLevel(applyView(children, sort, showHidden), depth + 1)
                }
            }
        }
        addLevel(top, 0)
        return rows
    }

    // --- 表示設定 ---

    fun setViewMode(mode: ViewMode) {
        viewModelScope.launch { settingsRepo.setViewMode(mode) }
    }

    fun setSortKey(key: SortKey) {
        viewModelScope.launch { settingsRepo.toggleSortKey(key) }
    }

    fun toggleFoldersFirst() {
        viewModelScope.launch { settingsRepo.toggleFoldersFirst() }
    }

    fun toggleShowHidden() {
        viewModelScope.launch { settingsRepo.toggleShowHidden() }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepo.setThemeMode(mode) }
    }

    fun completeOnboarding() {
        viewModelScope.launch { settingsRepo.setOnboardingDone(true) }
    }

    // toInt 切り捨てで小さな倍率変化が失われないよう、float の累積値を別に持つ
    private var iconSizeRaw: Float = -1f

    /** ピンチによるアイコンサイズ変更（SPEC §4.4: 48〜256dp。永続化はデバウンス） */
    fun scaleIconSize(factor: Float) {
        if (iconSizeRaw < 0f) iconSizeRaw = _state.value.iconSizeDp.toFloat()
        iconSizeRaw = (iconSizeRaw * factor).coerceIn(48f, 256f)
        val next = iconSizeRaw.toInt()
        if (next != _state.value.iconSizeDp) {
            _state.value = _state.value.copy(iconSizeDp = next)
        }
        iconSizePersistJob?.cancel()
        iconSizePersistJob = viewModelScope.launch {
            delay(400)
            settingsRepo.setIconSizeDp(next)
        }
    }

    private var colWidthsPersistJob: Job? = null

    /** リスト列幅のドラッグ変更（SPEC §4.4 列カスタマイズ。即時反映、永続化はデバウンス） */
    fun setListColumnWidths(dateDp: Int, sizeDp: Int, kindDp: Int) {
        val s = _state.value
        if (s.listDateWidthDp != dateDp || s.listSizeWidthDp != sizeDp ||
            s.listKindWidthDp != kindDp
        ) {
            _state.value = s.copy(
                listDateWidthDp = dateDp,
                listSizeWidthDp = sizeDp,
                listKindWidthDp = kindDp,
            )
        }
        colWidthsPersistJob?.cancel()
        colWidthsPersistJob = viewModelScope.launch {
            delay(400)
            settingsRepo.setListColumnWidths(dateDp, sizeDp, kindDp)
        }
    }

    fun notify(@StringRes messageRes: Int) {
        _events.trySend(BrowserEvent.Message(messageRes))
    }

    // --- ネットワーク接続（SPEC §7.2） ---

    fun requestAddConnection() {
        _state.value = _state.value.copy(
            editingConnection = ConnectionEntity(
                name = "", protocol = NetProtocol.SMB.scheme, host = "",
                port = NetProtocol.SMB.defaultPort, sharePath = "", username = "",
                savePassword = true,
            ),
        )
    }

    fun requestEditConnection(connection: ConnectionEntity) {
        _state.value = _state.value.copy(editingConnection = connection)
    }

    fun dismissConnectionDialog() {
        _state.value = _state.value.copy(editingConnection = null)
    }

    fun saveConnection(connection: ConnectionEntity, password: String?) {
        viewModelScope.launch {
            val id = connectionDao.upsert(connection)
            val connId = if (connection.id != 0L) connection.id else id
            // 「保存しない」に切り替えたら過去の保存値も必ず消す（古い値が優先されて詰まるのを防ぐ）
            if (!connection.savePassword) {
                credentialStore.setPassword(connId, null)
            }
            if (!password.isNullOrEmpty()) {
                if (connection.savePassword) {
                    credentialStore.setPassword(connId, password)
                }
                container.sessionPasswords[connId] = password
            }
            registry.invalidateConnection(connId)
            dismissConnectionDialog()
            _events.trySend(BrowserEvent.Message(R.string.connection_saved))
        }
    }

    fun deleteConnection(connection: ConnectionEntity) {
        viewModelScope.launch {
            connectionDao.delete(connection.id)
            credentialStore.clear(connection.id)
            container.sessionPasswords.remove(connection.id)
            registry.invalidateConnection(connection.id)
            dismissConnectionDialog()
            if (NetPaths.connectionId(_state.value.currentPath) == connection.id) {
                navigateTo(internalRoot, NavDirection.JUMP)
            }
        }
    }

    fun testConnection(connection: ConnectionEntity, password: String) {
        viewModelScope.launch {
            _events.trySend(BrowserEvent.Message(R.string.connection_testing))
            val result = NetworkTester.test(connection, password, credentialStore)
            _events.trySend(
                BrowserEvent.Message(
                    if (result.isSuccess) R.string.connection_test_ok else R.string.connection_test_ng,
                ),
            )
        }
    }

    fun openConnection(connection: ConnectionEntity) {
        // 平文 FTP は警告表示（SPEC §7.1）
        if (connection.protocol == NetProtocol.FTP.scheme) {
            _events.trySend(BrowserEvent.Message(R.string.ftp_plaintext_warning))
        }
        navigateTo(NetPaths.root(connection), NavDirection.FORWARD)
    }

    private fun askNetPassword(path: FsPath, retry: () -> Unit) {
        val connId = NetPaths.connectionId(path)
        viewModelScope.launch {
            val name = connectionDao.byId(connId)?.name ?: "?"
            pendingNetOp = retry
            _state.value = _state.value.copy(netPasswordAsk = connId to name)
        }
    }

    fun submitNetPassword(password: String) {
        val ask = _state.value.netPasswordAsk ?: return
        _state.value = _state.value.copy(netPasswordAsk = null)
        viewModelScope.launch {
            val conn = connectionDao.byId(ask.first)
            container.sessionPasswords[ask.first] = password
            if (conn?.savePassword == true) {
                credentialStore.setPassword(ask.first, password)
            }
            pendingNetOp?.invoke()
        }
    }

    fun cancelNetPassword() {
        _state.value = _state.value.copy(netPasswordAsk = null)
        pendingNetOp = null
        if (_state.value.isNetwork) {
            navigateTo(internalRoot, NavDirection.BACKWARD)
        }
    }

    // --- タグ（SPEC §6.3: 7色。ユーザー定義色は今後） ---

    fun toggleTag(entry: FsEntry, tag: String) {
        if (entry.path.scheme != "file") {
            _events.trySend(BrowserEvent.Message(R.string.tag_local_only))
            return
        }
        viewModelScope.launch {
            val path = entry.path.displayPath()
            val current = _state.value.tagsByKey[entry.path.key] ?: emptySet()
            if (tag in current) {
                tagDao.remove(path, tag)
            } else {
                tagDao.add(EntryTagEntity(path, tag))
            }
            val s = _state.value
            _state.value = s.copy(tagsByKey = loadTagsFor(s.entries + s.listRows.map { it.entry }))
        }
    }

    // --- 検索（SPEC §6.7: インクリメンタル。M5） ---

    fun enterSearch() {
        val s = _state.value
        if (s.isTrash || s.isArchive || s.isNetwork || s.currentPath.scheme == TAG_SCHEME) {
            _events.trySend(BrowserEvent.Message(R.string.search_local_only))
            return
        }
        _state.value = s.copy(searchActive = true, searchQuery = "")
    }

    fun exitSearch() {
        searchJob?.cancel()
        _state.value = _state.value.copy(searchActive = false, searchQuery = "", searching = false)
        refresh()
    }

    fun setSearchGlobal(global: Boolean) {
        _state.value = _state.value.copy(searchGlobal = global)
        setSearchQuery(_state.value.searchQuery)
    }

    fun setSearchQuery(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.value = _state.value.copy(searching = false)
            refresh()
            return
        }
        val root = if (_state.value.searchGlobal) internalRoot else _state.value.currentPath
        searchJob = viewModelScope.launch {
            delay(300) // 入力のデバウンス
            _state.value = _state.value.copy(searching = true)
            val results = mutableListOf<FsEntry>()
            withContext(Dispatchers.IO) {
                val rootFile = File(root.displayPath())
                var emitted = 0
                for (f in rootFile.walkTopDown()
                    .onEnter { !it.name.startsWith(".") && it.name != TrashManagerDirName }
                ) {
                    if (!kotlinx.coroutines.currentCoroutineContext().isActive) break
                    if (f == rootFile) continue
                    if (!f.name.contains(query, ignoreCase = true)) continue
                    val entry = runCatching {
                        providerFor(internalRoot)
                            .stat(LocalFileSystemProvider.fromAbsolutePath(f.absolutePath))
                    }.getOrNull() ?: continue
                    results += entry
                    emitted++
                    if (emitted % 30 == 0) {
                        val snapshot = results.toList()
                        withContext(Dispatchers.Main) { publishSearchResults(snapshot, done = false) }
                    }
                    if (emitted >= SEARCH_LIMIT) break
                }
            }
            publishSearchResults(results, done = true)
        }
    }

    private suspend fun publishSearchResults(results: List<FsEntry>, done: Boolean) {
        val s = _state.value
        if (!s.searchActive) return
        val visible = applyView(results, s.sort, s.showHidden)
        _state.value = s.copy(
            entries = visible,
            listRows = visible.map { TreeRow(it, 0, expanded = false) },
            tagsByKey = loadTagsFor(visible),
            searching = !done,
            loading = false,
            errorRes = null,
        )
    }

    // --- カラム / ギャラリー表示（SPEC §4.4。M5） ---

    /** カラム表示の各列の一覧（キャッシュ付き） */
    suspend fun loadChildren(path: FsPath): List<FsEntry> {
        columnCache[path.key]?.let { return it }
        val s = _state.value
        val listed = runCatching { listPath(path) }.getOrDefault(emptyList())
        val visible = applyView(listed, s.sort, s.showHidden)
        columnCache[path.key] = visible
        return visible
    }

    // --- 設定（SPEC §10。M6） ---

    fun showSettings() {
        _state.value = _state.value.copy(showSettings = true)
    }

    fun dismissSettings() {
        _state.value = _state.value.copy(showSettings = false)
    }

    fun setSingleTapOpen(value: Boolean) {
        viewModelScope.launch { settingsRepo.setSingleTapOpen(value) }
    }

    fun setDynamicColor(value: Boolean) {
        viewModelScope.launch { settingsRepo.setDynamicColor(value) }
    }

    fun setBiometricLock(value: Boolean) {
        viewModelScope.launch { settingsRepo.setBiometricLock(value) }
    }

    fun setTrashAutoDays(days: Int) {
        viewModelScope.launch { settingsRepo.setTrashAutoDays(days) }
    }

    fun clearCaches() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val cacheDir = getApplication<Application>().cacheDir
                listOf("thumbnails", "net-preview", "archive-preview").forEach {
                    File(cacheDir, it).deleteRecursively()
                }
            }
            _events.trySend(BrowserEvent.Message(R.string.cache_cleared))
        }
    }

    // --- 内部ヘルパー ---

    private suspend fun currentNames(): Set<String> = namesIn(_state.value.currentPath)

    private suspend fun namesIn(path: FsPath): Set<String> =
        runCatching { listPath(path).mapTo(mutableSetOf()) { it.name } }
            .getOrDefault(emptySet())

    // 1万件級のフォルダでもメインスレッドを塞がないようソートはバックグラウンドで行う
    private suspend fun applyView(
        list: List<FsEntry>,
        sort: SortSpec,
        showHidden: Boolean,
    ): List<FsEntry> = withContext(Dispatchers.Default) {
        val visible = if (showHidden) list else list.filter { !it.isHidden }
        val comparator: Comparator<FsEntry> = when (sort.key) {
            SortKey.NAME -> compareBy { it.name.lowercase() }
            SortKey.SIZE -> compareBy<FsEntry> { it.size }.thenBy { it.name.lowercase() }
            SortKey.DATE -> compareBy<FsEntry> { it.lastModified }.thenBy { it.name.lowercase() }
            SortKey.KIND -> compareBy<FsEntry>(
                { kindOrder(it.kind) },
                { kindLabel(it) },
            ).thenBy { it.name.lowercase() }
        }
        val directed = if (sort.ascending) comparator else comparator.reversed()
        val sorted = visible.sortedWith(directed)
        if (sort.foldersFirst) {
            sorted.sortedBy { !it.isDir } // 安定ソートでフォルダを先頭に
        } else {
            sorted
        }
    }

    private fun kindOrder(kind: EntryKind): Int = when (kind) {
        EntryKind.FOLDER -> 0
        EntryKind.IMAGE -> 1
        EntryKind.VIDEO -> 2
        EntryKind.AUDIO -> 3
        EntryKind.PDF -> 4
        EntryKind.TEXT -> 5
        EntryKind.ARCHIVE -> 6
        EntryKind.APK -> 7
        EntryKind.OTHER -> 8
    }

    companion object {
        private const val HISTORY_LIMIT = 50
        private const val SEARCH_LIMIT = 500
        const val TRASH_SCHEME = "trash"
        const val TAG_SCHEME = "tag"
        val TRASH_PATH = FsPath(TRASH_SCHEME, emptyList())
        private val TrashManagerDirName =
            io.github.hatake716.dango.data.fs.trash.TrashManager.TRASH_DIR_NAME

        /** SPEC §9 のタグ7色（id はサイドバー・DB のキー） */
        val TAG_COLORS = listOf("red", "orange", "yellow", "green", "blue", "purple", "gray")

        fun tagPath(color: String) = FsPath(TAG_SCHEME, listOf(color))
    }
}
