package io.github.hatake716.dango.domain.model

/**
 * すべてのファイルシステムで共通のパス表現（SPEC §8.2）。
 * UI層はスキーム（file / saf / smb / ...）を意識しない。
 */
data class FsPath(
    val scheme: String,
    val segments: List<String>,
) {
    val name: String get() = segments.lastOrNull() ?: "/"
    val parent: FsPath? get() = if (segments.isEmpty()) null else copy(segments = segments.dropLast(1))

    fun child(name: String): FsPath = copy(segments = segments + name)

    fun isDescendantOf(other: FsPath): Boolean =
        scheme == other.scheme &&
            segments.size >= other.segments.size &&
            segments.subList(0, other.segments.size) == other.segments

    fun displayPath(): String = "/" + segments.joinToString("/")

    val key: String get() = "$scheme:${displayPath()}"
}

enum class EntryKind {
    FOLDER, IMAGE, VIDEO, AUDIO, PDF, TEXT, ARCHIVE, APK, OTHER,
}

data class FsEntry(
    val path: FsPath,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long,
    val isHidden: Boolean,
    val kind: EntryKind,
    /** サムネイル表示に使えるURI（画像・動画。UIはこの文字列をローダに渡すだけ） */
    val previewUri: String?,
    /** OS制限でアクセス不可（Android/data 等）。グレー表示し、開くと理由を通知する（SPEC §3） */
    val isRestricted: Boolean = false,
    /** ローカル実体の URI（プレーヤー・共有などに渡す。UI が File を直接触らないための橋渡し） */
    val fileUri: String? = null,
    /** ゴミ箱項目のときだけ非 null（Room の TrashEntry id） */
    val trashId: Long? = null,
) {
    val extension: String
        get() = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
}

enum class ViewMode { ICON, LIST, COLUMN, GALLERY }

enum class SortKey { NAME, KIND, SIZE, DATE }

data class SortSpec(
    val key: SortKey = SortKey.NAME,
    val ascending: Boolean = true,
    val foldersFirst: Boolean = true,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 内部クリップボード（SPEC §6.3: 複数回ペースト可） */
enum class ClipboardMode { COPY, MOVE }

data class ClipboardState(
    val entries: List<FsEntry>,
    val mode: ClipboardMode,
)

/** 同名衝突時の選択（SPEC §6.3） */
enum class ConflictResolution { KEEP_BOTH, REPLACE, SKIP, CANCEL_ALL }

data class ConflictChoice(
    val resolution: ConflictResolution,
    val applyToAll: Boolean,
)

/** Quick Look で表示できる種別（SPEC §6.5。フォント・アーカイブ・APK は M3 以降） */
val PREVIEWABLE_KINDS = setOf(
    EntryKind.IMAGE, EntryKind.VIDEO, EntryKind.AUDIO, EntryKind.PDF, EntryKind.TEXT,
)
