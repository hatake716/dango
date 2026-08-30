package io.github.hatake716.dango.data.info

import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import io.github.hatake716.dango.domain.model.EntryKind
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.domain.model.FsPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** 情報ウィンドウの詳細（SPEC §6.3 情報を見る） */
data class EntryDetails(
    val createdAt: Long?,
    val permissions: String,
    /** 画像の EXIF や動画のコーデック情報など、種別ごとの追加行 */
    val extras: List<Pair<String, String>>,
)

class InfoLoader {

    suspend fun load(entry: FsEntry): EntryDetails = withContext(Dispatchers.IO) {
        val file = File(entry.path.displayPath())
        val created = runCatching {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                .creationTime().toMillis().takeIf { it > 0 }
        }.getOrNull()
        val permissions = buildString {
            append(if (file.canRead()) "読み出し" else "アクセス不可")
            if (file.canWrite()) append(" / 書き込み")
        }
        val extras = when (entry.kind) {
            EntryKind.IMAGE -> imageExtras(file)
            EntryKind.VIDEO, EntryKind.AUDIO -> mediaExtras(file, entry.kind)
            else -> emptyList()
        }
        EntryDetails(created, permissions, extras)
    }

    /** フォルダサイズの非同期集計（SPEC §6.3）。途中経過を随時 emit する */
    fun folderSize(path: FsPath): Flow<Pair<Long, Int>> = flow {
        var bytes = 0L
        var count = 0
        File(path.displayPath()).walkTopDown().forEach { f ->
            if (f.isFile) {
                bytes += f.length()
                count++
                if (count % 256 == 0) emit(bytes to count)
            }
        }
        emit(bytes to count)
    }.flowOn(Dispatchers.IO)

    /** MD5 / SHA-256 のオンデマンド計算（SPEC §6.3） */
    suspend fun hash(path: FsPath, algorithm: String): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance(algorithm)
        File(path.displayPath()).inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                digest.update(buf, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun imageExtras(file: File): List<Pair<String, String>> = runCatching {
        val exif = ExifInterface(file)
        buildList {
            val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            if (w > 0 && h > 0) add("サイズ（ピクセル）" to "$w × $h")
            exif.getAttribute(ExifInterface.TAG_MAKE)?.let { add("メーカー" to it) }
            exif.getAttribute(ExifInterface.TAG_MODEL)?.let { add("機種" to it) }
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { add("撮影日時" to it) }
            exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let {
                val sec = it.toDoubleOrNull()
                if (sec != null && sec > 0 && sec < 1) {
                    add("露出時間" to "1/${(1 / sec).toInt()} 秒")
                } else {
                    add("露出時間" to "$it 秒")
                }
            }
            exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { add("F値" to "f/$it") }
            exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { add("ISO" to it) }
            exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { raw ->
                val parts = raw.split('/')
                val mm = if (parts.size == 2) {
                    parts[0].toDoubleOrNull()?.div(parts[1].toDoubleOrNull() ?: 1.0)
                } else {
                    raw.toDoubleOrNull()
                }
                mm?.let { add("焦点距離" to "%.1f mm".format(it)) }
            }
        }
    }.getOrDefault(emptyList())

    private fun mediaExtras(file: File, kind: EntryKind): List<Pair<String, String>> = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            buildList {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.let { ms ->
                        val sec = ms / 1000
                        add("長さ" to "%d:%02d".format(sec / 60, sec % 60))
                    }
                if (kind == EntryKind.VIDEO) {
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    if (w != null && h != null) add("解像度" to "$w × $h")
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    ?.let { add("形式" to it) }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toLongOrNull()?.let { add("ビットレート" to "${it / 1000} kbps") }
                if (kind == EntryKind.AUDIO) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?.let { add("アーティスト" to it) }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                        ?.let { add("アルバム" to it) }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.let { add("タイトル" to it) }
                }
            }
        } finally {
            retriever.release()
        }
    }.getOrDefault(emptyList())
}
