package io.github.hatake716.dango.data.fs.local

import android.net.Uri
import android.os.StatFs
import io.github.hatake716.dango.data.fs.Capability
import io.github.hatake716.dango.data.fs.FileSystemProvider
import io.github.hatake716.dango.data.fs.ProgressSink
import io.github.hatake716.dango.domain.model.EntryKind
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.domain.model.FsPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okio.Sink
import okio.Source
import okio.appendingSink
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException

/** フルアクセスモード用の java.io.File ベース実装（SPEC §3, §8.1 data/fs/local） */
class LocalFileSystemProvider : FileSystemProvider {

    override val scheme: String = SCHEME

    override fun list(path: FsPath): Flow<FsEntry> = flow {
        val dir = path.toFile()
        val children = dir.listFiles()
            ?: throw IOException("cannot list: ${dir.absolutePath}")
        for (file in children) {
            emit(file.toEntry())
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun stat(path: FsPath): FsEntry = withContext(Dispatchers.IO) {
        val file = path.toFile()
        if (!file.exists()) throw IOException("not found: ${file.absolutePath}")
        file.toEntry()
    }

    override suspend fun mkdir(path: FsPath) = withContext(Dispatchers.IO) {
        val file = path.toFile()
        if (!file.mkdirs() && !file.isDirectory) {
            throw IOException("mkdir failed: ${file.absolutePath}")
        }
    }

    override suspend fun rename(from: FsPath, to: FsPath) = withContext(Dispatchers.IO) {
        if (!from.toFile().renameTo(to.toFile())) {
            throw IOException("rename failed: ${from.displayPath()} -> ${to.displayPath()}")
        }
    }

    override suspend fun delete(path: FsPath, recursive: Boolean) = withContext(Dispatchers.IO) {
        val file = path.toFile()
        val ok = if (recursive) file.deleteRecursively() else file.delete()
        if (!ok) throw IOException("delete failed: ${file.absolutePath}")
    }

    override suspend fun openRead(path: FsPath): Source = withContext(Dispatchers.IO) {
        path.toFile().source()
    }

    override suspend fun openWrite(path: FsPath, append: Boolean): Sink = withContext(Dispatchers.IO) {
        val file = path.toFile()
        if (append) file.appendingSink() else file.sink()
    }

    override suspend fun copy(from: FsPath, to: FsPath, progress: ProgressSink): Boolean =
        withContext(Dispatchers.IO) {
            val src = from.toFile()
            val total = src.length()
            openRead(from).use { source ->
                openWrite(to, append = false).buffer().use { sink ->
                    var copied = 0L
                    val buf = okio.Buffer()
                    while (true) {
                        val read = source.read(buf, 256 * 1024L)
                        if (read == -1L) break
                        sink.write(buf, read)
                        copied += read
                        progress.onProgress(copied, total)
                    }
                }
            }
            true
        }

    override fun capabilities(): Set<Capability> = setOf(Capability.RENAME, Capability.TRASH)

    override suspend fun freeSpace(path: FsPath): Long? = withContext(Dispatchers.IO) {
        runCatching { StatFs(path.toFile().absolutePath).availableBytes }.getOrNull()
    }

    private fun FsPath.toFile(): File {
        require(scheme == SCHEME) { "unexpected scheme: $scheme" }
        return File(displayPath())
    }

    private fun File.toEntry(): FsEntry {
        val dir = isDirectory
        val ext = name.substringAfterLast('.', "").lowercase()
        val kind = if (dir) EntryKind.FOLDER else kindOf(ext)
        return FsEntry(
            path = fromAbsolutePath(absolutePath),
            name = name,
            isDir = dir,
            size = if (dir) -1L else length(),
            lastModified = lastModified(),
            isHidden = name.startsWith("."),
            kind = kind,
            previewUri = if (kind == EntryKind.IMAGE && ext != "svg") {
                Uri.fromFile(this).toString()
            } else {
                null
            },
            isRestricted = dir && isRestrictedDir(absolutePath),
        )
    }

    companion object {
        const val SCHEME = "file"

        // Android 11+ でアプリからアクセスできない共有ストレージ直下のディレクトリ（SPEC §3）
        private val RESTRICTED_SUFFIXES = listOf("/Android/data", "/Android/obb")

        private fun isRestrictedDir(absolutePath: String): Boolean =
            absolutePath.startsWith("/storage/") &&
                RESTRICTED_SUFFIXES.any { absolutePath.endsWith(it) }

        fun fromAbsolutePath(absolutePath: String): FsPath =
            FsPath(SCHEME, absolutePath.split('/').filter { it.isNotEmpty() })

        private val IMAGE_EXT = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "svg",
        )
        private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts")
        private val AUDIO_EXT = setOf("mp3", "aac", "flac", "ogg", "wav", "opus", "m4a", "mid")
        private val TEXT_EXT = setOf(
            "txt", "md", "json", "xml", "yaml", "yml", "csv", "log", "ini", "conf",
            "kt", "kts", "py", "js", "ts", "sh", "fish", "nix", "c", "h", "cpp", "rs",
            "go", "java", "html", "css", "toml", "properties", "gradle",
        )
        private val ARCHIVE_EXT = setOf(
            "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "zst", "rar",
        )

        private fun kindOf(ext: String): EntryKind = when (ext) {
            in IMAGE_EXT -> EntryKind.IMAGE
            in VIDEO_EXT -> EntryKind.VIDEO
            in AUDIO_EXT -> EntryKind.AUDIO
            "pdf" -> EntryKind.PDF
            in TEXT_EXT -> EntryKind.TEXT
            in ARCHIVE_EXT -> EntryKind.ARCHIVE
            "apk" -> EntryKind.APK
            else -> EntryKind.OTHER
        }
    }
}
