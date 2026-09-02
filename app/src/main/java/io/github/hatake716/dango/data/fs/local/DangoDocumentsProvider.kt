package io.github.hatake716.dango.data.fs.local

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import io.github.hatake716.dango.R
import java.io.File
import java.io.FileNotFoundException

/**
 * システムのファイルピッカー（ACTION_OPEN_DOCUMENT / ACTION_CREATE_DOCUMENT /
 * ACTION_OPEN_DOCUMENT_TREE）に dango を場所として載せる DocumentsProvider。
 * OPEN_DOCUMENT はシステムピッカー専用のインテントでアプリが直接受けることは
 * できないため、この形で統合する。内部ストレージをフルアクセス権限で公開する
 * （呼び出し側のアクセスはシステム（DocumentsUI）が MANAGE_DOCUMENTS で仲介する）。
 */
class DangoDocumentsProvider : DocumentsProvider() {

    private val baseDir: File
        get() = Environment.getExternalStorageDirectory()

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        // フルアクセス未許可の間はルートを出しても開けないので載せない
        if (!Environment.isExternalStorageManager()) return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            add(Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
            add(Root.COLUMN_SUMMARY, context!!.getString(R.string.loc_internal))
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY,
            )
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_AVAILABLE_BYTES, baseDir.freeSpace)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        addFileRow(cursor, fileFor(documentId), documentId)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val dir = fileFor(parentDocumentId)
        val children = dir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() },
        ) ?: emptyList()
        for (child in children) {
            addFileRow(cursor, child, docIdFor(child))
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = fileFor(parentDocumentId)
        var file = File(parent, displayName)
        // 同名衝突は連番で回避（Finder/SAF の慣例）
        var n = 1
        while (file.exists()) {
            val dot = displayName.lastIndexOf('.')
            val name = if (dot > 0) {
                "${displayName.substring(0, dot)} ($n)${displayName.substring(dot)}"
            } else {
                "$displayName ($n)"
            }
            file = File(parent, name)
            n++
        }
        val ok = if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile()
        if (!ok) throw FileNotFoundException("作成できません: ${file.name}")
        return docIdFor(file)
    }

    override fun deleteDocument(documentId: String) {
        val file = fileFor(documentId)
        if (!file.deleteRecursively()) {
            throw FileNotFoundException("削除できません: ${file.name}")
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileFor(documentId)
        val dest = File(file.parentFile, displayName)
        if (dest.exists() || !file.renameTo(dest)) {
            throw FileNotFoundException("リネームできません: ${file.name}")
        }
        return docIdFor(dest)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith("$parentDocumentId/") || parentDocumentId == ROOT_DOC_ID &&
            documentId.startsWith("$ROOT_DOC_ID:")

    override fun getDocumentType(documentId: String): String = mimeTypeOf(fileFor(documentId))

    private fun addFileRow(cursor: MatrixCursor, file: File, docId: String) {
        if (!file.exists()) throw FileNotFoundException("存在しません: $docId")
        var flags = 0
        if (file.isDirectory) {
            if (file.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (file.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (file.canWrite() && docId != ROOT_DOC_ID) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId)
            add(Document.COLUMN_DISPLAY_NAME, if (docId == ROOT_DOC_ID) context!!.getString(R.string.loc_internal) else file.name)
            add(Document.COLUMN_MIME_TYPE, mimeTypeOf(file))
            add(Document.COLUMN_SIZE, if (file.isFile) file.length() else null)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mimeTypeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    /** docId: "primary"（ルート）または "primary:相対パス" */
    private fun fileFor(documentId: String): File {
        if (documentId == ROOT_DOC_ID) return baseDir
        require(documentId.startsWith("$ROOT_DOC_ID:")) { "不明なdocId: $documentId" }
        val rel = documentId.substringAfter(':')
        val file = File(baseDir, rel)
        // パストラバーサル防止（DocumentsUI 以外からの不正な docId）
        if (!file.canonicalPath.startsWith(baseDir.canonicalPath)) {
            throw FileNotFoundException("不正なパス: $documentId")
        }
        return file
    }

    private fun docIdFor(file: File): String {
        val rel = file.absolutePath.removePrefix(baseDir.absolutePath).trimStart('/')
        return if (rel.isEmpty()) ROOT_DOC_ID else "$ROOT_DOC_ID:$rel"
    }

    companion object {
        private const val ROOT_ID = "dango-primary"
        const val ROOT_DOC_ID = "primary"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
