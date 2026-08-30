package io.github.hatake716.dango.ui.browser

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.fs.FileSystemProvider
import io.github.hatake716.dango.data.fs.local.LocalFileSystemProvider
import io.github.hatake716.dango.data.fs.local.LocalLocations
import io.github.hatake716.dango.data.prefs.Settings
import io.github.hatake716.dango.data.prefs.SettingsRepository
import io.github.hatake716.dango.domain.model.EntryKind
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.domain.model.FsPath
import io.github.hatake716.dango.domain.model.SortKey
import io.github.hatake716.dango.domain.model.SortSpec
import io.github.hatake716.dango.domain.model.ThemeMode
import io.github.hatake716.dango.domain.model.ViewMode
import io.github.hatake716.dango.ui.util.kindLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** コンテンツ切替アニメーションの向き（SPEC §5: 開くと戻るで逆再生） */
enum class NavDirection { FORWARD, BACKWARD, JUMP }

/** サイドバー項目（SPEC §4.3。ユーザー追加・タグは M1 以降） */
data class SidebarItem(
    val id: String,
    @param:StringRes val labelRes: Int,
    val path: FsPath,
)

data class BrowserUiState(
    val currentPath: FsPath,
    val entries: List<FsEntry> = emptyList(),
    val loading: Boolean = true,
    @param:StringRes val errorRes: Int? = null,
    val selection: Set<String> = emptySet(),
    val viewMode: ViewMode = ViewMode.ICON,
    val sort: SortSpec = SortSpec(),
    val showHidden: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val navDirection: NavDirection = NavDirection.JUMP,
    val freeSpaceBytes: Long? = null,
)

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val provider: FileSystemProvider = LocalFileSystemProvider()
    private val settingsRepo = SettingsRepository(app)

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
    )

    // 初期値 null: DataStore の実値が届く前に既定値で UI を確定させない（起動時フラッシュ防止）
    val settings: StateFlow<Settings?> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _state = MutableStateFlow(BrowserUiState(currentPath = internalRoot))
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private val _messages = Channel<Int>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val backStack = ArrayDeque<FsPath>()
    private val forwardStack = ArrayDeque<FsPath>()
    private var rawEntries: List<FsEntry> = emptyList()
    private var loadJob: Job? = null

    init {
        // 設定の変化（表示モード・ソート・隠しファイル）を一覧に反映する
        viewModelScope.launch {
            settings.filterNotNull().collect { s ->
                val visible = applyView(rawEntries, s.sort, s.showHidden)
                val visibleKeys = visible.mapTo(mutableSetOf()) { it.path.key }
                _state.value = _state.value.copy(
                    viewMode = s.viewMode,
                    sort = s.sort,
                    showHidden = s.showHidden,
                    entries = visible,
                    // 非表示になったエントリが選択に残ると件数表示が不整合になる
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
        val parent = _state.value.currentPath.parent ?: return
        navigateTo(parent, NavDirection.BACKWARD)
    }

    fun reload() {
        load(_state.value.currentPath, NavDirection.JUMP)
    }

    private fun load(path: FsPath, direction: NavDirection) {
        loadJob?.cancel()
        _state.value = _state.value.copy(
            currentPath = path,
            loading = true,
            errorRes = null,
            selection = emptySet(),
            navDirection = direction,
            canGoBack = backStack.isNotEmpty(),
            canGoForward = forwardStack.isNotEmpty(),
        )
        loadJob = viewModelScope.launch {
            val result = runCatching { provider.list(path).toList() }
            val free = provider.freeSpace(path)
            result.fold(
                onSuccess = { list ->
                    rawEntries = list
                    _state.value = _state.value.copy(
                        entries = applyView(list, _state.value.sort, _state.value.showHidden),
                        loading = false,
                        freeSpaceBytes = free,
                    )
                },
                onFailure = {
                    rawEntries = emptyList()
                    _state.value = _state.value.copy(
                        entries = emptyList(),
                        loading = false,
                        errorRes = R.string.error_cannot_open,
                        freeSpaceBytes = free,
                    )
                },
            )
        }
    }

    // --- 選択と開く（SPEC §6.1: タップで選択、再タップ/ダブルタップで開く） ---

    fun onEntryTap(entry: FsEntry) {
        val key = entry.path.key
        if (key in _state.value.selection) {
            open(entry)
        } else {
            _state.value = _state.value.copy(selection = setOf(key))
        }
    }

    fun onEntryDoubleTap(entry: FsEntry) {
        open(entry)
    }

    fun onEntryLongPress(entry: FsEntry) {
        val key = entry.path.key
        val selection = _state.value.selection
        _state.value = _state.value.copy(
            selection = if (key in selection) selection - key else selection + key,
        )
    }

    fun clearSelection() {
        if (_state.value.selection.isNotEmpty()) {
            _state.value = _state.value.copy(selection = emptySet())
        }
    }

    private fun open(entry: FsEntry) {
        when {
            entry.isRestricted -> _messages.trySend(R.string.restricted_folder)
            entry.isDir -> navigateTo(entry.path, NavDirection.FORWARD)
            else -> _messages.trySend(R.string.preview_not_yet)
        }
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

    fun notify(@StringRes messageRes: Int) {
        _messages.trySend(messageRes)
    }

    // --- 並べ替え・フィルタ ---

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

    private companion object {
        const val HISTORY_LIMIT = 50
    }
}
