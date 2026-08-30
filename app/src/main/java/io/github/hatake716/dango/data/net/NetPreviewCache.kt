package io.github.hatake716.dango.data.net

import io.github.hatake716.dango.data.fs.ProviderRegistry
import io.github.hatake716.dango.domain.model.FsEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * ネットワークファイルのプレビュー用一時ダウンロード（SPEC §7.3）。
 * 上限管理は単純化（最終アクセスの古いものから合計 1GB を超えた分を削除）。
 */
class NetPreviewCache(private val cacheDir: File) {

    private val root: File get() = File(cacheDir, "net-preview")

    suspend fun fetch(entry: FsEntry, registry: ProviderRegistry): File =
        withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("MD5")
                .digest("${entry.path.key}|${entry.lastModified}|${entry.size}".toByteArray())
                .joinToString("") { "%02x".format(it) }
            val dir = File(root, digest).apply { mkdirs() }
            val out = File(dir, entry.name)
            if (out.exists() && out.length() == entry.size) return@withContext out
            val provider = registry.forPath(entry.path)
            val tmp = File(dir, ".${entry.name}.tmp")
            try {
                provider.openRead(entry.path).use { source ->
                    tmp.sink().buffer().use { sink ->
                        sink.writeAll(source)
                    }
                }
                if (!tmp.renameTo(out)) throw IOException("cache rename failed")
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
            trim()
            out
        }

    private fun trim() {
        val files = root.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.parentFile?.deleteRecursively()
            if (total <= MAX_BYTES) break
        }
    }

    companion object {
        private const val MAX_BYTES = 1024L * 1024 * 1024
    }
}
