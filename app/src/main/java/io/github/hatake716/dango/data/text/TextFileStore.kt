package io.github.hatake716.dango.data.text

import io.github.hatake716.dango.domain.model.FsPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class LineEnding(val literal: String, val label: String) {
    LF("\n", "LF"),
    CRLF("\r\n", "CRLF"),
}

data class TextDocument(
    val text: String,
    val charsetName: String,
    val lineEnding: LineEnding,
    /** 2MB 超で先頭のみ読み込んだ場合 true（SPEC §6.5: 「すべて読み込む」で継続） */
    val truncated: Boolean,
    val totalBytes: Long,
    /** §6.5.1: 2MB 超は読み取り専用 */
    val editable: Boolean,
)

/** テキストの読み込み（文字コード自動判定）と原子的保存（SPEC §6.5, §6.5.1） */
class TextFileStore {

    suspend fun load(path: FsPath, full: Boolean = false): TextDocument =
        withContext(Dispatchers.IO) {
            val file = File(path.displayPath())
            val total = file.length()
            // 「すべて読み込む」も OOM を避けるため上限を設ける
            val limit = if (full) VIEW_LIMIT_BYTES else EDIT_LIMIT_BYTES
            val bytes = file.inputStream().use { input ->
                val size = minOf(total, limit).toInt()
                val buf = ByteArray(size)
                var off = 0
                while (off < size) {
                    val read = input.read(buf, off, size - off)
                    if (read < 0) break
                    off += read
                }
                if (off == buf.size) buf else buf.copyOf(off)
            }
            val truncated = total > limit
            val charset = detectCharset(bytes)
            val raw = decode(bytes, charset)
            val lineEnding = if (raw.contains("\r\n")) LineEnding.CRLF else LineEnding.LF
            TextDocument(
                text = raw.replace("\r\n", "\n"),
                charsetName = charset.name(),
                lineEnding = lineEnding,
                truncated = truncated,
                totalBytes = total,
                editable = total <= EDIT_LIMIT_BYTES,
            )
        }

    /**
     * 一時ファイルへ書き込み、fsync してから rename で置き換える（§6.5.1: 途中クラッシュで元を壊さない）。
     * 改行コードは指定の形式へ揃える。
     * 元の文字コードで表現できない文字が含まれる場合は UTF-8 で保存する（黙った '?' 置換を避ける）。
     * @return 実際に保存した文字コード名
     */
    suspend fun save(
        path: FsPath,
        text: String,
        charsetName: String,
        lineEnding: LineEnding,
    ): String = withContext(Dispatchers.IO) {
        val target = File(path.displayPath())
        val parent = target.parentFile ?: throw IOException("親フォルダがありません")
        val tmp = File(parent, ".${target.name}.dango-tmp")
        val body = text.replace("\n", lineEnding.literal)
        val requested = runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
        val (bytes, usedCharset) = encodeStrictOrUtf8(body, requested)
        java.io.FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync() // 電源断で新旧両方を失わないため、rename 前に実体を確定させる
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("保存に失敗しました")
        }
        usedCharset.name()
    }

    private fun encodeStrictOrUtf8(body: String, charset: Charset): Pair<ByteArray, Charset> =
        runCatching {
            val buffer = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(body))
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            bytes to charset
        }.getOrElse {
            body.toByteArray(Charsets.UTF_8) to Charsets.UTF_8
        }

    /** UTF-8 → Shift_JIS → EUC-JP の順で厳密デコードを試す（SPEC §6.5） */
    private fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return Charsets.UTF_8
        }
        for (name in listOf("UTF-8", "Shift_JIS", "EUC-JP")) {
            val cs = Charset.forName(name)
            if (strictlyDecodable(bytes, cs)) return cs
        }
        return Charsets.UTF_8
    }

    private fun strictlyDecodable(bytes: ByteArray, charset: Charset): Boolean =
        runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        }.getOrDefault(false)

    private fun decode(bytes: ByteArray, charset: Charset): String {
        var body = bytes
        if (charset == Charsets.UTF_8 && body.size >= 3 &&
            body[0] == 0xEF.toByte() && body[1] == 0xBB.toByte() && body[2] == 0xBF.toByte()
        ) {
            body = body.copyOfRange(3, body.size) // BOM は表示しない
        }
        return String(body, charset)
    }

    companion object {
        const val EDIT_LIMIT_BYTES = 2L * 1024 * 1024
        const val VIEW_LIMIT_BYTES = 32L * 1024 * 1024
    }
}
