package io.github.hatake716.dango.data.fs

import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.domain.model.FsPath
import kotlinx.coroutines.flow.Flow
import okio.Sink
import okio.Source

enum class Capability {
    RENAME, SERVER_SIDE_COPY, TRASH, RESUME, XATTR,
}

fun interface ProgressSink {
    fun onProgress(bytesCopied: Long, totalBytes: Long)
}

/**
 * ファイルシステム抽象化（SPEC §8.2）。
 * UI層から直接 java.io.File を触らず、必ずこのインターフェース経由でアクセスする。
 */
interface FileSystemProvider {
    val scheme: String

    fun list(path: FsPath): Flow<FsEntry>

    suspend fun stat(path: FsPath): FsEntry

    suspend fun mkdir(path: FsPath)

    suspend fun rename(from: FsPath, to: FsPath)

    suspend fun delete(path: FsPath, recursive: Boolean)

    suspend fun openRead(path: FsPath): Source

    suspend fun openWrite(path: FsPath, append: Boolean): Sink

    /** サーバ側コピーができた場合は true。false ならストリームコピーにフォールバックする */
    suspend fun copy(from: FsPath, to: FsPath, progress: ProgressSink): Boolean

    fun capabilities(): Set<Capability>

    /** path が属するボリュームの空き容量（バイト）。取得できない場合は null */
    suspend fun freeSpace(path: FsPath): Long?
}
