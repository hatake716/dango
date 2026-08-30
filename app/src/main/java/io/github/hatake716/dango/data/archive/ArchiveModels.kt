package io.github.hatake716.dango.data.archive

import io.github.hatake716.dango.domain.model.FsPath
import java.io.IOException

/** 対応アーカイブ形式（SPEC §6.4） */
enum class ArchiveFormat(val wrapsTar: Boolean = false, val singleFile: Boolean = false) {
    ZIP,
    SEVEN_Z,
    RAR,
    TAR,
    TAR_GZ(wrapsTar = true),
    TAR_BZ2(wrapsTar = true),
    TAR_XZ(wrapsTar = true),
    TAR_ZST(wrapsTar = true),
    GZ(singleFile = true),
    BZ2(singleFile = true),
    XZ(singleFile = true),
    ZST(singleFile = true),
    ;

    companion object {
        fun of(fileName: String): ArchiveFormat? {
            val n = fileName.lowercase()
            return when {
                n.endsWith(".tar.gz") || n.endsWith(".tgz") -> TAR_GZ
                n.endsWith(".tar.bz2") || n.endsWith(".tbz2") -> TAR_BZ2
                n.endsWith(".tar.xz") || n.endsWith(".txz") -> TAR_XZ
                n.endsWith(".tar.zst") -> TAR_ZST
                n.endsWith(".tar") -> TAR
                n.endsWith(".zip") -> ZIP
                n.endsWith(".7z") -> SEVEN_Z
                n.endsWith(".rar") -> RAR
                n.endsWith(".gz") -> GZ
                n.endsWith(".bz2") -> BZ2
                n.endsWith(".xz") -> XZ
                n.endsWith(".zst") -> ZST
                else -> null
            }
        }

        /** 単一圧縮ファイルの展開後の名前（foo.txt.gz → foo.txt） */
        fun stripSingleExtension(fileName: String): String {
            val n = fileName.lowercase()
            val ext = listOf(".gz", ".bz2", ".xz", ".zst").firstOrNull { n.endsWith(it) }
                ?: return fileName
            return fileName.dropLast(ext.length).ifEmpty { fileName }
        }
    }
}

/** アーカイブ内の1エントリ（表示名は文字コード判定済み。rawKey は形式ネイティブの識別子） */
data class ArchiveEntryMeta(
    val segments: List<String>,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
    val rawKey: String,
)

data class ArchiveIndex(
    val entries: List<ArchiveEntryMeta>,
    val encodingUsed: String,
    val encrypted: Boolean,
) {
    val totalBytes: Long get() = entries.sumOf { if (it.size > 0) it.size else 0L }

    /** innerPath 直下の子（中間ディレクトリはエントリが無くても合成する） */
    fun childrenOf(inner: List<String>): List<ArchiveEntryMeta> {
        val result = LinkedHashMap<String, ArchiveEntryMeta>()
        for (e in entries) {
            if (e.segments.size <= inner.size) continue
            if (e.segments.subList(0, inner.size) != inner) continue
            val childName = e.segments[inner.size]
            if (e.segments.size == inner.size + 1) {
                result[childName] = e
            } else if (childName !in result) {
                // 中間ディレクトリを合成
                result[childName] = ArchiveEntryMeta(
                    segments = inner + childName,
                    isDir = true,
                    size = -1,
                    mtime = 0,
                    rawKey = "",
                )
            }
        }
        return result.values.toList()
    }
}

class ArchivePasswordException(message: String? = null) : IOException(message)

class ArchiveNoSpaceException : IOException()

class ArchiveUnsupportedException(message: String) : IOException(message)

/** アーカイブ内ブラウズ用の仮想パス（scheme=archive、先頭セグメントに実ファイルの絶対パスを持つ） */
object ArchivePaths {
    const val SCHEME = "archive"

    fun root(archiveAbsolutePath: String): FsPath =
        FsPath(SCHEME, listOf(archiveAbsolutePath))

    fun archiveFile(path: FsPath): String = path.segments.first()

    fun inner(path: FsPath): List<String> = path.segments.drop(1)

    fun isArchivePath(path: FsPath): Boolean = path.scheme == SCHEME
}
