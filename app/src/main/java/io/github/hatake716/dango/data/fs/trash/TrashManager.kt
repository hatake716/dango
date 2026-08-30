package io.github.hatake716.dango.data.fs.trash

import android.net.Uri
import io.github.hatake716.dango.data.db.TrashDao
import io.github.hatake716.dango.data.db.TrashEntryEntity
import io.github.hatake716.dango.data.fs.NameUtils
import io.github.hatake716.dango.data.fs.local.LocalFileSystemProvider
import io.github.hatake716.dango.domain.model.EntryKind
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.domain.model.FsPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ゴミ箱（SPEC §6.6）。
 * `<内部ストレージ>/.Trash-dango/` に rename で移動し、元パスと削除日時を Room に記録する。
 * （仕様の `.Trash-<uid>` は再インストールで uid が変わり復元不能になるため固定名を採用。docs/PROGRESS.md 参照）
 */
class TrashManager(
    private val internalRootPath: String,
    private val dao: TrashDao,
) {
    private val trashDir: File get() = File(internalRootPath, TRASH_DIR_NAME)

    /** ゴミ箱へ移動し、Room に記録した id のリストを返す */
    suspend fun moveToTrash(entries: List<FsEntry>): List<Long> = withContext(Dispatchers.IO) {
        trashDir.mkdirs()
        val ids = mutableListOf<Long>()
        for (entry in entries) {
            val src = File(entry.path.displayPath())
            if (!src.exists()) continue
            val trashedName = "${System.currentTimeMillis()}_${ids.size}_${entry.name}"
            val dst = File(trashDir, trashedName)
            if (!src.renameTo(dst)) {
                throw IOException("ゴミ箱への移動に失敗: ${src.absolutePath}")
            }
            ids += dao.insert(
                TrashEntryEntity(
                    originalPath = src.absolutePath,
                    trashedName = trashedName,
                    displayName = entry.name,
                    isDir = entry.isDir,
                    size = entry.size,
                    deletedAt = System.currentTimeMillis(),
                ),
            )
        }
        ids
    }

    /** ゴミ箱一覧を FsEntry として返す（path はゴミ箱内の実体、trashId で復元先を引く） */
    suspend fun list(): List<FsEntry> = withContext(Dispatchers.IO) {
        dao.listAll().mapNotNull { e ->
            val file = File(trashDir, e.trashedName)
            if (!file.exists()) {
                // 実体が消えている行は掃除する
                dao.deleteByIds(listOf(e.id))
                return@mapNotNull null
            }
            file.toTrashEntry(e)
        }
    }

    /** 元の場所へ戻す。戻せた元パスのリストを返す（SPEC §6.6: 元に戻す） */
    suspend fun restore(ids: List<Long>): List<String> = withContext(Dispatchers.IO) {
        val restored = mutableListOf<String>()
        for (e in dao.byIds(ids)) {
            val src = File(trashDir, e.trashedName)
            if (!src.exists()) {
                dao.deleteByIds(listOf(e.id))
                continue
            }
            val original = File(e.originalPath)
            original.parentFile?.mkdirs()
            val parent = original.parentFile ?: continue
            val siblings = parent.list()?.toSet() ?: emptySet()
            val name = NameUtils.uniqueName(siblings, original.name, e.isDir)
            val dst = File(parent, name)
            if (src.renameTo(dst)) {
                dao.deleteByIds(listOf(e.id))
                restored += dst.absolutePath
            }
        }
        restored
    }

    /** 完全削除（SPEC §6.6。確認ダイアログは UI 側の責務） */
    suspend fun deleteForever(ids: List<Long>): Unit = withContext(Dispatchers.IO) {
        for (e in dao.byIds(ids)) {
            File(trashDir, e.trashedName).deleteRecursively()
        }
        dao.deleteByIds(ids)
    }

    suspend fun emptyTrash(): Unit = withContext(Dispatchers.IO) {
        dao.listAll().forEach { File(trashDir, it.trashedName).deleteRecursively() }
        dao.deleteAll()
        trashDir.listFiles()?.forEach { it.deleteRecursively() } // 記録漏れの残骸も掃除
    }

    /** 30日経過項目の自動削除（SPEC §6.6。起動時に呼ぶ） */
    suspend fun purgeExpired(days: Long = 30): Unit = withContext(Dispatchers.IO) {
        val threshold = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days)
        val expired = dao.olderThan(threshold)
        if (expired.isNotEmpty()) {
            deleteForever(expired.map { it.id })
        }
    }

    private fun File.toTrashEntry(e: TrashEntryEntity): FsEntry {
        val base = LocalFileSystemProvider.fromAbsolutePath(absolutePath)
        val ext = e.displayName.substringAfterLast('.', "").lowercase()
        return FsEntry(
            path = base,
            name = e.displayName,
            isDir = e.isDir,
            size = if (e.isDir) -1L else length(),
            lastModified = e.deletedAt,
            isHidden = false,
            kind = if (e.isDir) EntryKind.FOLDER else LocalFileSystemProvider.kindOfExtension(ext),
            previewUri = if (!e.isDir && LocalFileSystemProvider.hasPreview(ext)) {
                Uri.fromFile(this).toString()
            } else {
                null
            },
            fileUri = Uri.fromFile(this).toString(),
            trashId = e.id,
        )
    }

    companion object {
        const val TRASH_DIR_NAME = ".Trash-dango"
    }
}
