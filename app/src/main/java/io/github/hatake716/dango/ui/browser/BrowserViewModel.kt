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
import io.github.hatake716.dango.data.fs.NameUtils
import io.github.hatake716.dango.data.fs.local.LocalFileSystemProvider
import io.github.hatake716.dango.data.fs.local.LocalLocations
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
)

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as DangoApp).container
    private val provider = container.fileSystemProvider
    private val settingsRepo = container.settingsRepository
    private val trashManager = container.trashManager
    private val transferManager = container.transferManager
    private val archiveManager = container.archiveManager
    val infoLoader = container.infoLoader
    val textFileStore = container.textFileStore

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
    private var loadJob: Job? = null
    private var iconSizePersistJob: Job? = null

    /** リストのツリー展開状態（フォルダ移動でリセット） */
    private val expandedKeys = mutableSetOf<String>()
    private val childrenCache = mutableMapOf<String, List<FsEntry>>()

    init {
        viewModelScope.launch { trashManager.purgeExpired() } // 30日で自動削除（SPEC §6.6）
        viewModelScope.launch {
            settings.filterNotNull().collect { s ->
                val visible = applyView(rawEntries, s.sort, s.showHidden)
                val rows = buildTreeRows(visible, s.sort, s.showHidden)
                // ツリー展開中の子行も「見えている」ので選択の整合判定に含める
                val visibleKeys = rows.mapTo(mutableSetOf()) { it.entry.path.key } +
                    visible.map { it.path.key }
                _state.value = _state.value.copy(
                    viewMode = s.viewMode,
                    sort = s.sort,
                    showHidden = s.showHidden,
                    iconSizeDp = s.iconSizeDp,
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
        val parent = current.parent ?: return
        navigateTo(parent, NavDirection.BACKWARD)
    }

    fun reload() {
        load(_state.value.currentPath, NavDirection.JUMP)
    }

    /** 表示を静かに更新する（アニメーションや選択リセットを伴わない） */
    private fun refresh(thenRenameKey: String? = null) {
        val path = _state.value.currentPath
        viewModelScope.launch {
            val result = runCatching { listPath(path) }
            // 完了までにユーザーが別フォルダへ移動していたら、この結果は捨てる
            if (_state.value.currentPath != path) return@launch
            result.onSuccess { list ->
                rawEntries = list
                // 展開中のツリー子要素も再取得して古い表示を残さない
                for (key in expandedKeys.toList()) {
                    val entry = (list + childrenCache.values.flatten()).find { it.path.key == key }
                    if (entry != null) {
                        childrenCache[key] =
                            runCatching { provider.list(entry.path).toList() }.getOrDefault(emptyList())
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
                    selection = if (thenRenameKey != null) {
                        setOf(thenRenameKey)
                    } else {
                        s.selection intersect visibleKeys
                    },
                    renamingKey = thenRenameKey,
                    loading = false,
                    errorRes = null,
                )
            }
        }
    }

    private suspend fun listPath(path: FsPath): List<FsEntry> = when (path.scheme) {
        TRASH_SCHEME -> trashManager.list()
        ArchivePaths.SCHEME -> listArchive(path)
        else -> provider.list(path).toList()
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
            canGoBack = backStack.isNotEmpty(),
            canGoForward = forwardStack.isNotEmpty(),
            quickLookIndex = null,
        )
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
            val free = if (path.scheme == "file") {
                provider.freeSpace(path)
            } else {
                provider.freeSpace(internalRoot)
            }
            result.fold(
                onSuccess = { list ->
                    rawEntries = list
                    val s = _state.value
                    val visible = applyView(list, s.sort, s.showHidden)
                    _state.value = s.copy(
                        entries = visible,
                        listRows = buildTreeRows(visible, s.sort, s.showHidden),
                        loading = false,
                        freeSpaceBytes = free,
                    )
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

    fun onEntryTap(entry: FsEntry) {
        val key = entry.path.key
        val s = _state.value
        if (s.renamingKey != null) return
        if (s.selectionMode) {
            toggleSelect(key)
            return
        }
        if (key in s.selection) {
            open(entry)
        } else {
            _state.value = s.copy(selection = setOf(key))
        }
    }

    fun onEntryDoubleTap(entry: FsEntry) {
        if (_state.value.selectionMode) return
        open(entry)
    }

    fun onEntryLongPress(entry: FsEntry) {
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
            // アーカイブを開く＝既定は「同名フォルダに展開」（SPEC §6.4）
            entry.kind == EntryKind.ARCHIVE && !_state.value.isTrash ->
                extractArchive(entry, wrapInFolder = true)
            else -> openQuickLook(entry)
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
                    val real = runCatching { provider.stat(LocalFileSystemProvider.fromAbsolutePath(cached.absolutePath)) }
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
        }
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
            runCatching { provider.mkdir(path) }
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
                withContext(Dispatchers.IO) { provider.openWrite(path, append = false).close() }
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
        val entry = (rawEntries + childrenCache.values.flatten()).find { it.path.key == key }
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
            runCatching { provider.rename(entry.path, newPath) }
                .onSuccess {
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
                runCatching { provider.rename(entry.path, newPath) }.onFailure { failed++ }
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

    fun paste() {
        val s = _state.value
        val clipboard = s.clipboard ?: return
        if (s.isTrash || s.isArchive || anyBusy) return
        // フォルダを自分自身（または子孫）へは貼り付けできない（SPEC §6.3。無限再帰防止）
        if (clipboard.entries.any { e ->
                e.isDir && (s.currentPath == e.path || s.currentPath.isDescendantOf(e.path))
            }
        ) {
            _events.trySend(BrowserEvent.Message(R.string.paste_into_itself))
            return
        }
        TransferService.start(getApplication())
        transferManager.start(
            entries = clipboard.entries,
            destDir = s.currentPath,
            move = clipboard.mode == ClipboardMode.MOVE,
        ) { result ->
            viewModelScope.launch {
                if (clipboard.mode == ClipboardMode.MOVE && !result.cancelled) {
                    _state.value = _state.value.copy(clipboard = null)
                }
                val pasted = result.copiedNames
                    .mapTo(mutableSetOf()) { _state.value.currentPath.child(it).key }
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

    /** ドラッグされたエントリ群を destDir へ移動する */
    fun moveByDrag(keys: Set<String>, destDir: FsPath) {
        val s = _state.value
        if (s.isTrash || s.isArchive || anyBusy || destDir.scheme != "file") return
        val pool = (s.entries + s.listRows.map { it.entry }).distinctBy { it.path.key }
        val entries = pool.filter { it.path.key in keys && !it.isRestricted }
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
        if (_state.value.isTrash) {
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
            if (pending.emptyAll) {
                trashManager.emptyTrash()
            } else {
                trashManager.deleteForever(pending.entries.mapNotNull { it.trashId })
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
        if (mode == ViewMode.COLUMN || mode == ViewMode.GALLERY) return // M5 で対応
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

    fun notify(@StringRes messageRes: Int) {
        _events.trySend(BrowserEvent.Message(messageRes))
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
        const val TRASH_SCHEME = "trash"
        val TRASH_PATH = FsPath(TRASH_SCHEME, emptyList())
    }
}
