package io.github.hatake716.dango.data.archive

import com.github.junrar.Archive
import io.github.hatake716.dango.data.fs.NameUtils
import io.github.hatake716.dango.data.transfer.OperationKind
import io.github.hatake716.dango.data.transfer.TransferProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

enum class CompressFormat(val extension: String) {
    ZIP("zip"), TAR_GZ("tar.gz"), SEVEN_Z("7z"),
}

data class ArchiveOpResult(
    val createdNames: List<String>,
    val cancelled: Boolean,
    val error: ArchiveError? = null,
)

enum class ArchiveError { PASSWORD, NO_SPACE, UNSUPPORTED, FAILED }

/**
 * 圧縮・解凍エンジン（SPEC §6.4）。
 * Zip Slip 対策・空き容量チェック・進捗と通知は TransferManager と同じ流儀で提供する。
 */
class ArchiveManager(private val cacheDir: File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

    private var currentJob: Job? = null
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    val isBusy: Boolean get() = busy.get()

    fun cancel() {
        currentJob?.cancel()
    }

    // インデックスは同一（パス・更新時刻・パスワード・文字コード指定）ならキャッシュする
    private val indexCache = object : LinkedHashMap<String, ArchiveIndex>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArchiveIndex>?) =
            size > 4
    }

    suspend fun index(
        archive: File,
        password: String?,
        encodingOverride: String?,
    ): ArchiveIndex = withContext(Dispatchers.IO) {
        val format = ArchiveFormat.of(archive.name)
            ?: throw ArchiveUnsupportedException("未対応の形式です")
        val key = "${archive.absolutePath}|${archive.lastModified()}|$encodingOverride|${password != null}"
        synchronized(indexCache) { indexCache[key] }?.let { return@withContext it }
        val index = ArchiveIndexer.index(archive, format, password, encodingOverride)
        synchronized(indexCache) { indexCache[key] = index }
        index
    }

    /**
     * 展開（SPEC §6.4）。wrapInFolder=true で「同名フォルダに展開」。
     * 展開後サイズを事前見積もりして空き容量を確認し、Zip Slip はエントリごとに遮断する。
     */
    fun extractAll(
        archive: File,
        destRoot: File,
        wrapInFolder: Boolean,
        password: String?,
        encodingOverride: String?,
        onFinished: (ArchiveOpResult) -> Unit,
    ) {
        if (!busy.compareAndSet(false, true)) return
        currentJob = scope.launch {
            var result = ArchiveOpResult(emptyList(), cancelled = true)
            var wrapDir: File? = null
            // wrap なし展開の失敗・キャンセル時に書きかけの成果物を掃除するための記録
            val createdTop = mutableSetOf<File>()
            fun cleanup() {
                wrapDir?.deleteRecursively()
                createdTop.forEach { it.deleteRecursively() }
            }
            try {
                _progress.value = TransferProgress(OperationKind.EXTRACT, 0, 0, 0, 0, archive.name)
                val format = ArchiveFormat.of(archive.name)
                    ?: throw ArchiveUnsupportedException("未対応の形式です")
                val idx = index(archive, password, encodingOverride)

                // 空き容量チェック（SPEC §6.4）。展開後サイズ不明時は圧縮サイズを下限として使う
                val estimate = if (idx.totalBytes > 0) idx.totalBytes else archive.length()
                if (destRoot.usableSpace < estimate + SPACE_MARGIN_BYTES) {
                    throw ArchiveNoSpaceException()
                }

                val destDir: File
                if (wrapInFolder) {
                    val existing = destRoot.list()?.toSet() ?: emptySet()
                    val base = archiveBaseName(archive.name)
                    destDir = File(destRoot, NameUtils.uniqueName(existing, base, isDir = true))
                    if (!destDir.mkdirs()) throw IOException("mkdir failed: $destDir")
                    wrapDir = destDir
                } else {
                    destDir = destRoot
                }

                val created = extractInto(archive, format, idx, destDir, password, wrapInFolder, createdTop)
                result = ArchiveOpResult(
                    createdNames = if (wrapInFolder) listOf(destDir.name) else created,
                    cancelled = false,
                )
            } catch (e: CancellationException) {
                cleanup()
                throw e
            } catch (e: ArchivePasswordException) {
                cleanup()
                result = ArchiveOpResult(emptyList(), false, ArchiveError.PASSWORD)
            } catch (e: org.apache.commons.compress.PasswordRequiredException) {
                cleanup()
                result = ArchiveOpResult(emptyList(), false, ArchiveError.PASSWORD)
            } catch (e: ArchiveNoSpaceException) {
                result = ArchiveOpResult(emptyList(), false, ArchiveError.NO_SPACE)
            } catch (e: ArchiveUnsupportedException) {
                result = ArchiveOpResult(emptyList(), false, ArchiveError.UNSUPPORTED)
            } catch (_: Exception) {
                cleanup()
                result = ArchiveOpResult(emptyList(), false, ArchiveError.FAILED)
            } finally {
                _progress.value = null
                busy.set(false)
                onFinished(result)
            }
        }
    }

    private suspend fun extractInto(
        archive: File,
        format: ArchiveFormat,
        idx: ArchiveIndex,
        destDir: File,
        password: String?,
        wrapped: Boolean,
        createdTop: MutableSet<File>,
    ): List<String> {
        val destCanonical = destDir.canonicalPath
        val totalBytes = idx.totalBytes
        val totalFiles = idx.entries.count { !it.isDir }
        var doneBytes = 0L
        var doneFiles = 0

        // wrap しない場合、既存名と衝突するトップレベルは連番で退避する
        val topRemap = mutableMapOf<String, String>()
        if (!wrapped) {
            val existing = destDir.list()?.toMutableSet() ?: mutableSetOf()
            for (top in idx.entries.mapNotNull { it.segments.firstOrNull() }.distinct()) {
                if (existing.any { it.equals(top, ignoreCase = true) }) {
                    val isDir = idx.entries.any { it.segments.firstOrNull() == top && (it.isDir || it.segments.size > 1) }
                    val renamed = NameUtils.uniqueName(existing, top, isDir)
                    topRemap[top] = renamed
                    existing += renamed
                } else {
                    existing += top
                }
            }
        }

        fun resolveTarget(meta: ArchiveEntryMeta): File {
            val segments = meta.segments.toMutableList()
            topRemap[segments.first()]?.let { segments[0] = it }
            val target = File(destDir, segments.joinToString("/"))
            // Zip Slip 対策（SPEC §6.4）
            if (!target.canonicalPath.startsWith(destCanonical + File.separator)) {
                throw IOException("unsafe entry path: ${meta.segments}")
            }
            if (!wrapped) {
                createdTop += File(destDir, segments.first())
            }
            return target
        }

        suspend fun writeStream(input: InputStream, target: File, name: String) {
            target.parentFile?.mkdirs()
            target.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buf)
                    if (read < 0) break
                    out.write(buf, 0, read)
                    doneBytes += read
                    _progress.value = TransferProgress(
                        OperationKind.EXTRACT, doneBytes, totalBytes, doneFiles, totalFiles, name,
                    )
                }
            }
            doneFiles++
        }

        val metaByRaw = idx.entries.associateBy { it.rawKey }

        when (format) {
            ArchiveFormat.ZIP -> {
                val zip = net.lingala.zip4j.ZipFile(archive)
                zip.charset = Charsets.ISO_8859_1
                if (password != null) zip.setPassword(password.toCharArray())
                for (header in zip.fileHeaders) {
                    coroutineContext.ensureActive()
                    val meta = metaByRaw[header.fileName] ?: continue
                    val target = resolveTarget(meta)
                    if (meta.isDir) {
                        target.mkdirs()
                        continue
                    }
                    try {
                        zip.getInputStream(header).use { writeStream(it, target, meta.segments.last()) }
                    } catch (e: ZipException) {
                        if (e.type == ZipException.Type.WRONG_PASSWORD ||
                            (header.isEncrypted && password == null)
                        ) {
                            throw ArchivePasswordException()
                        }
                        throw e
                    }
                }
            }
            ArchiveFormat.SEVEN_Z -> {
                try {
                    val sz = SevenZFile.Builder()
                        .setFile(archive)
                        .apply { if (password != null) setPassword(password.toCharArray()) }
                        .get()
                    sz.use { seven ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val e = seven.nextEntry ?: break
                            val meta = metaByRaw[e.name] ?: continue
                            val target = resolveTarget(meta)
                            if (meta.isDir) {
                                target.mkdirs()
                                continue
                            }
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out ->
                                val buf = ByteArray(256 * 1024)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val read = seven.read(buf)
                                    if (read < 0) break
                                    out.write(buf, 0, read)
                                    doneBytes += read
                                    _progress.value = TransferProgress(
                                        OperationKind.EXTRACT, doneBytes, totalBytes, doneFiles, totalFiles,
                                        meta.segments.last(),
                                    )
                                }
                            }
                            doneFiles++
                        }
                    }
                } catch (e: org.apache.commons.compress.PasswordRequiredException) {
                    throw ArchivePasswordException(e.message)
                } catch (e: IOException) {
                    // パスワード指定時のチェックサム不一致は誤パスワードとみなして再入力を促す
                    if (password != null) throw ArchivePasswordException(e.message) else throw e
                }
            }
            ArchiveFormat.RAR -> {
                val rar = if (password != null) Archive(archive, password) else Archive(archive)
                rar.use { r ->
                    for (header in r.fileHeaders) {
                        coroutineContext.ensureActive()
                        val meta = metaByRaw[header.fileName] ?: continue
                        val target = resolveTarget(meta)
                        if (meta.isDir) {
                            target.mkdirs()
                            continue
                        }
                        target.parentFile?.mkdirs()
                        try {
                            target.outputStream().use { out ->
                                r.extractFile(header, out)
                            }
                        } catch (e: com.github.junrar.exception.RarException) {
                            if (password != null || idx.encrypted) {
                                throw ArchivePasswordException(e.message)
                            }
                            throw IOException(e.message, e)
                        }
                        doneBytes += (meta.size.takeIf { it > 0 } ?: 0)
                        doneFiles++
                        _progress.value = TransferProgress(
                            OperationKind.EXTRACT, doneBytes, totalBytes, doneFiles, totalFiles,
                            meta.segments.last(),
                        )
                    }
                }
            }
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2,
            ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_ZST,
            -> {
                ArchiveIndexer.openTarStream(archive, format).use { tar ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val e = tar.nextEntry ?: break
                        if (e.isSymbolicLink || e.isLink) continue
                        val meta = metaByRaw[e.name] ?: continue
                        val target = resolveTarget(meta)
                        if (meta.isDir) {
                            target.mkdirs()
                            continue
                        }
                        writeStream(tar, target, meta.segments.last())
                    }
                }
            }
            ArchiveFormat.GZ, ArchiveFormat.BZ2, ArchiveFormat.XZ, ArchiveFormat.ZST -> {
                val meta = idx.entries.first()
                val target = resolveTarget(meta)
                ArchiveIndexer.openSingleStream(archive, format).use {
                    writeStream(it, target, meta.segments.last())
                }
            }
        }
        return idx.entries.mapNotNull { it.segments.firstOrNull() }
            .distinct()
            .map { topRemap[it] ?: it }
    }

    /** アーカイブ内の1エントリをプレビュー用キャッシュへ展開する（SPEC §6.4 アーカイブ内ブラウズ） */
    suspend fun extractEntryToCache(
        archive: File,
        entrySegments: List<String>,
        password: String?,
        encodingOverride: String?,
    ): File = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("MD5")
            .digest("${archive.absolutePath}|${archive.lastModified()}|${entrySegments.joinToString("/")}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dir = File(cacheDir, "archive-preview/$digest").apply { mkdirs() }
        val out = File(dir, entrySegments.last())
        if (out.exists() && out.length() > 0) return@withContext out
        // 書きかけを正として再利用しないよう、一時名に展開してから rename する
        val tmp = File(dir, ".${entrySegments.last()}.tmp")
        extractSingle(archive, entrySegments, tmp, password, encodingOverride)
        if (!tmp.renameTo(out)) {
            tmp.delete()
            throw IOException("cache rename failed")
        }
        out
    }

    /** アーカイブ内の1エントリを実フォルダへ書き出す（右クリック「フォルダに書き出す」） */
    suspend fun extractEntryTo(
        archive: File,
        entrySegments: List<String>,
        destDir: File,
        password: String?,
        encodingOverride: String?,
    ): String = withContext(Dispatchers.IO) {
        val existing = destDir.list()?.toSet() ?: emptySet()
        val name = NameUtils.uniqueName(existing, entrySegments.last(), isDir = false)
        extractSingle(archive, entrySegments, File(destDir, name), password, encodingOverride)
        name
    }

    private suspend fun extractSingle(
        archive: File,
        entrySegments: List<String>,
        target: File,
        password: String?,
        encodingOverride: String?,
    ) = try {
        extractSingleInner(archive, entrySegments, target, password, encodingOverride)
    } catch (e: org.apache.commons.compress.PasswordRequiredException) {
        target.delete()
        throw ArchivePasswordException(e.message)
    } catch (e: Exception) {
        target.delete()
        throw e
    }

    private suspend fun extractSingleInner(
        archive: File,
        entrySegments: List<String>,
        target: File,
        password: String?,
        encodingOverride: String?,
    ) {
        val format = ArchiveFormat.of(archive.name)
            ?: throw ArchiveUnsupportedException("未対応の形式です")
        val idx = index(archive, password, encodingOverride)
        val meta = idx.entries.find { it.segments == entrySegments && !it.isDir }
            ?: throw IOException("entry not found")
        target.parentFile?.mkdirs()

        fun copy(input: InputStream, out: OutputStream) {
            input.copyTo(out, 256 * 1024)
        }

        when (format) {
            ArchiveFormat.ZIP -> {
                val zip = net.lingala.zip4j.ZipFile(archive)
                zip.charset = Charsets.ISO_8859_1
                if (password != null) zip.setPassword(password.toCharArray())
                val header = zip.fileHeaders.find { it.fileName == meta.rawKey }
                    ?: throw IOException("entry not found")
                if (header.isEncrypted && password == null) throw ArchivePasswordException()
                try {
                    zip.getInputStream(header).use { input ->
                        target.outputStream().use { copy(input, it) }
                    }
                } catch (e: ZipException) {
                    if (e.type == ZipException.Type.WRONG_PASSWORD) throw ArchivePasswordException()
                    throw e
                }
            }
            ArchiveFormat.SEVEN_Z -> {
                val sz = SevenZFile.Builder()
                    .setFile(archive)
                    .apply { if (password != null) setPassword(password.toCharArray()) }
                    .get()
                sz.use { seven ->
                    while (true) {
                        val e = seven.nextEntry ?: throw IOException("entry not found")
                        if (e.name == meta.rawKey) {
                            target.outputStream().use { out ->
                                val buf = ByteArray(256 * 1024)
                                while (true) {
                                    val read = seven.read(buf)
                                    if (read < 0) break
                                    out.write(buf, 0, read)
                                }
                            }
                            break
                        }
                    }
                }
            }
            ArchiveFormat.RAR -> {
                val rar = if (password != null) Archive(archive, password) else Archive(archive)
                rar.use { r ->
                    val header = r.fileHeaders.find { it.fileName == meta.rawKey }
                        ?: throw IOException("entry not found")
                    target.outputStream().use { r.extractFile(header, it) }
                }
            }
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2,
            ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_ZST,
            -> {
                ArchiveIndexer.openTarStream(archive, format).use { tar ->
                    while (true) {
                        val e = tar.nextEntry ?: throw IOException("entry not found")
                        if (e.name == meta.rawKey) {
                            target.outputStream().use { copy(tar, it) }
                            break
                        }
                    }
                }
            }
            ArchiveFormat.GZ, ArchiveFormat.BZ2, ArchiveFormat.XZ, ArchiveFormat.ZST -> {
                ArchiveIndexer.openSingleStream(archive, format).use { input ->
                    target.outputStream().use { copy(input, it) }
                }
            }
        }
    }

    /**
     * 圧縮（SPEC §6.4: zip 既定 / tar.gz / 7z。パスワードは zip のみ）。
     * 「圧縮後に元を削除」はゴミ箱経由にするため呼び出し側（VM）が行う。
     */
    fun compress(
        sources: List<File>,
        destDir: File,
        format: CompressFormat,
        level: Int,
        password: String?,
        multiBaseName: String,
        onFinished: (ArchiveOpResult) -> Unit,
    ) {
        if (!busy.compareAndSet(false, true)) return
        currentJob = scope.launch {
            var result = ArchiveOpResult(emptyList(), cancelled = true)
            var target: File? = null
            try {
                val existing = destDir.list()?.toSet() ?: emptySet()
                // Finder 同様、単一なら <フルネーム>.zip、複数なら アーカイブ.zip（SPEC §6.4)
                val baseName = when {
                    sources.size > 1 -> multiBaseName
                    sources.first().isDirectory ->
                        NameUtils.splitExtension(sources.first().name, isDir = true).first
                    else -> sources.first().name // ファイルは拡張子ごと残す（foo.txt.zip）
                }
                val targetName = NameUtils.uniqueName(existing, "$baseName.${format.extension}", isDir = false)
                val out = File(destDir, targetName)
                target = out

                var totalBytes = 0L
                var totalFiles = 0
                sources.forEach { src ->
                    src.walkTopDown().forEach { if (it.isFile) { totalFiles++; totalBytes += it.length() } }
                }
                var doneBytes = 0L
                var doneFiles = 0
                fun progress(name: String, delta: Long, fileDone: Boolean) {
                    doneBytes += delta
                    if (fileDone) doneFiles++
                    _progress.value = TransferProgress(
                        OperationKind.COMPRESS, doneBytes, totalBytes, doneFiles, totalFiles, name,
                    )
                }
                _progress.value = TransferProgress(OperationKind.COMPRESS, 0, totalBytes, 0, totalFiles, targetName)

                when (format) {
                    CompressFormat.ZIP -> compressZip(sources, out, level, password, ::progress)
                    CompressFormat.TAR_GZ -> compressTarGz(sources, out, ::progress)
                    CompressFormat.SEVEN_Z -> compressSevenZ(sources, out, ::progress)
                }

                result = ArchiveOpResult(listOf(targetName), cancelled = false)
            } catch (e: CancellationException) {
                target?.delete()
                throw e
            } catch (_: Exception) {
                target?.delete()
                result = ArchiveOpResult(emptyList(), false, ArchiveError.FAILED)
            } finally {
                _progress.value = null
                busy.set(false)
                onFinished(result)
            }
        }
    }

    private suspend fun compressZip(
        sources: List<File>,
        out: File,
        level: Int,
        password: String?,
        progress: (String, Long, Boolean) -> Unit,
    ) {
        val zipLevel = when {
            level <= 1 -> CompressionLevel.FASTEST
            level >= 3 -> CompressionLevel.MAXIMUM
            else -> CompressionLevel.NORMAL
        }
        val stream = if (password != null) {
            ZipOutputStream(out.outputStream().buffered(), password.toCharArray())
        } else {
            ZipOutputStream(out.outputStream().buffered())
        }
        stream.use { zos ->
            suspend fun add(file: File, base: String) {
                coroutineContext.ensureActive()
                val rel = if (base.isEmpty()) file.name else "$base/${file.name}"
                if (file.isDirectory) {
                    file.listFiles()?.forEach { add(it, rel) }
                } else {
                    val params = ZipParameters().apply {
                        fileNameInZip = rel
                        compressionLevel = zipLevel
                        if (password != null) {
                            isEncryptFiles = true
                            encryptionMethod = EncryptionMethod.AES
                            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                        }
                    }
                    zos.putNextEntry(params)
                    file.inputStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buf)
                            if (read < 0) break
                            zos.write(buf, 0, read)
                            progress(file.name, read.toLong(), false)
                        }
                    }
                    zos.closeEntry()
                    progress(file.name, 0, true)
                }
            }
            sources.forEach { add(it, "") }
        }
    }

    private suspend fun compressTarGz(
        sources: List<File>,
        out: File,
        progress: (String, Long, Boolean) -> Unit,
    ) {
        TarArchiveOutputStream(GzipCompressorOutputStream(out.outputStream().buffered())).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            suspend fun add(file: File, base: String) {
                coroutineContext.ensureActive()
                val rel = if (base.isEmpty()) file.name else "$base/${file.name}"
                val entry = TarArchiveEntry(file, rel)
                tar.putArchiveEntry(entry)
                if (file.isDirectory) {
                    tar.closeArchiveEntry()
                    file.listFiles()?.forEach { add(it, rel) }
                } else {
                    file.inputStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buf)
                            if (read < 0) break
                            tar.write(buf, 0, read)
                            progress(file.name, read.toLong(), false)
                        }
                    }
                    tar.closeArchiveEntry()
                    progress(file.name, 0, true)
                }
            }
            sources.forEach { add(it, "") }
        }
    }

    private suspend fun compressSevenZ(
        sources: List<File>,
        out: File,
        progress: (String, Long, Boolean) -> Unit,
    ) {
        SevenZOutputFile(out).use { seven ->
            suspend fun add(file: File, base: String) {
                coroutineContext.ensureActive()
                val rel = if (base.isEmpty()) file.name else "$base/${file.name}"
                val entry = seven.createArchiveEntry(file, rel)
                seven.putArchiveEntry(entry)
                if (file.isDirectory) {
                    seven.closeArchiveEntry()
                    file.listFiles()?.forEach { add(it, rel) }
                } else {
                    file.inputStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buf)
                            if (read < 0) break
                            seven.write(buf, 0, read)
                            progress(file.name, read.toLong(), false)
                        }
                    }
                    seven.closeArchiveEntry()
                    progress(file.name, 0, true)
                }
            }
            sources.forEach { add(it, "") }
        }
    }

    companion object {
        private const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024

        fun archiveBaseName(fileName: String): String {
            val n = fileName.lowercase()
            val compound = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst")
                .firstOrNull { n.endsWith(it) }
            if (compound != null) return fileName.dropLast(compound.length)
            val dot = fileName.lastIndexOf('.')
            return if (dot > 0) fileName.substring(0, dot) else fileName
        }
    }
}
