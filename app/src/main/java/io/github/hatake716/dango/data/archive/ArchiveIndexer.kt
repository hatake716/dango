package io.github.hatake716.dango.data.archive

import com.github.junrar.Archive
import com.github.junrar.exception.UnsupportedRarV5Exception
import net.lingala.zip4j.exception.ZipException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * アーカイブのエントリ一覧を作る（SPEC §6.4）。
 * zip のエントリ名は UTF-8 → Shift_JIS → CP437 の順で自動判定する（文字化け対策）。
 */
object ArchiveIndexer {

    fun index(
        file: File,
        format: ArchiveFormat,
        password: String?,
        encodingOverride: String?,
    ): ArchiveIndex = when (format) {
        ArchiveFormat.ZIP -> indexZip(file, encodingOverride)
        ArchiveFormat.SEVEN_Z -> indexSevenZ(file, password)
        ArchiveFormat.RAR -> indexRar(file, password)
        ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2,
        ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_ZST,
        -> indexTar(file, format)
        ArchiveFormat.GZ, ArchiveFormat.BZ2, ArchiveFormat.XZ, ArchiveFormat.ZST ->
            indexSingle(file)
    }

    private fun indexZip(file: File, encodingOverride: String?): ArchiveIndex {
        val zip = net.lingala.zip4j.ZipFile(file)
        // ISO-8859-1 はバイト透過なので、いったんこれで読み取り生バイトを復元して判定する
        zip.charset = Charsets.ISO_8859_1
        val headers = try {
            zip.fileHeaders
        } catch (e: ZipException) {
            throw ArchiveUnsupportedException(e.message ?: "zip open failed")
        }
        val rawSamples = mutableListOf<ByteArray>()
        for (h in headers) {
            if (!h.isFileNameUTF8Encoded) {
                rawSamples += h.fileName.toByteArray(Charsets.ISO_8859_1)
            }
        }
        val charset = encodingOverride?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: detectCharset(rawSamples)
        var encrypted = false
        val entries = headers.map { h ->
            if (h.isEncrypted) encrypted = true
            val name = if (h.isFileNameUTF8Encoded) {
                h.fileName
            } else {
                String(h.fileName.toByteArray(Charsets.ISO_8859_1), charset)
            }
            ArchiveEntryMeta(
                segments = splitName(name),
                isDir = h.isDirectory,
                size = h.uncompressedSize,
                mtime = runCatching {
                    net.lingala.zip4j.util.Zip4jUtil.dosToExtendedEpochTme(h.lastModifiedTime)
                }.getOrDefault(0L),
                rawKey = h.fileName, // ISO-8859-1 読みの名前が zip4j 上の識別子
            )
        }.filter { it.segments.isNotEmpty() }
        return ArchiveIndex(entries, charset.name(), encrypted)
    }

    private fun indexSevenZ(file: File, password: String?): ArchiveIndex {
        val sevenZ = try {
            SevenZFile.Builder()
                .setFile(file)
                .apply { if (password != null) setPassword(password.toCharArray()) }
                .get()
        } catch (e: org.apache.commons.compress.PasswordRequiredException) {
            throw ArchivePasswordException(e.message)
        } catch (e: java.io.IOException) {
            // ヘッダ暗号化 7z に誤パスワードを渡すとチェックサム系の IOException になる。
            // パスワード指定時は破損と区別できないため、再入力を促す
            if (password != null) throw ArchivePasswordException(e.message)
            throw e
        }
        sevenZ.use { sz ->
            val allEntries = sz.entries.toList()
            // ヘッダ非暗号化・本文のみ AES（7z の既定）を検出する
            val contentEncrypted = allEntries.any { e ->
                e.contentMethods?.any {
                    it.method == org.apache.commons.compress.archivers.sevenz.SevenZMethod.AES256SHA256
                } == true
            }
            if (contentEncrypted && password == null) throw ArchivePasswordException()
            val entries = allEntries.mapNotNull { e ->
                val name = e.name ?: return@mapNotNull null
                ArchiveEntryMeta(
                    segments = splitName(name),
                    isDir = e.isDirectory,
                    size = if (e.hasStream()) e.size else -1,
                    mtime = runCatching { e.lastModifiedDate?.time ?: 0L }.getOrDefault(0L),
                    rawKey = name,
                )
            }.filter { it.segments.isNotEmpty() }
            return ArchiveIndex(entries, "UTF-16", password != null || contentEncrypted)
        }
    }

    private fun indexRar(file: File, password: String?): ArchiveIndex {
        val archive = try {
            if (password != null) Archive(file, password) else Archive(file)
        } catch (e: UnsupportedRarV5Exception) {
            throw ArchiveUnsupportedException("RAR5 形式は未対応です")
        } catch (e: Exception) {
            // 誤パスワード時はヘッダ破損系の例外になる。パスワード指定時は再入力を促す
            if (password != null) throw ArchivePasswordException(e.message)
            throw e
        }
        archive.use { rar ->
            // ヘッダ暗号化だけでなくファイル単位の暗号化も見る
            val anyEncrypted = rar.isEncrypted || rar.fileHeaders.any { it.isEncrypted }
            if (anyEncrypted && password == null) throw ArchivePasswordException()
            val entries = rar.fileHeaders.map { h ->
                val name = h.fileName.replace('\\', '/')
                ArchiveEntryMeta(
                    segments = splitName(name),
                    isDir = h.isDirectory,
                    size = h.fullUnpackSize,
                    mtime = runCatching { h.mTime?.time ?: 0L }.getOrDefault(0L),
                    rawKey = h.fileName,
                )
            }.filter { it.segments.isNotEmpty() }
            return ArchiveIndex(entries, "UTF-8", anyEncrypted)
        }
    }

    private fun indexTar(file: File, format: ArchiveFormat): ArchiveIndex {
        openTarStream(file, format).use { tar ->
            val entries = mutableListOf<ArchiveEntryMeta>()
            while (true) {
                val e = tar.nextEntry ?: break
                if (e.isSymbolicLink || e.isLink) continue // SPEC §6.4: シンボリックリンクは無視
                val segments = splitName(e.name)
                if (segments.isEmpty()) continue
                entries += ArchiveEntryMeta(
                    segments = segments,
                    isDir = e.isDirectory,
                    size = if (e.isDirectory) -1 else e.size,
                    mtime = e.modTime?.time ?: 0L,
                    rawKey = e.name,
                )
            }
            return ArchiveIndex(entries, "UTF-8", encrypted = false)
        }
    }

    private fun indexSingle(file: File): ArchiveIndex {
        val name = ArchiveFormat.stripSingleExtension(file.name)
        // gz は末尾4バイト（ISIZE）に展開後サイズ mod 2^32 を持つ。空き容量チェックに使う
        val size = if (file.name.lowercase().endsWith(".gz") && file.length() >= 4) {
            runCatching {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(raf.length() - 4)
                    val b = ByteArray(4)
                    raf.readFully(b)
                    ((b[3].toLong() and 0xFF) shl 24) or ((b[2].toLong() and 0xFF) shl 16) or
                        ((b[1].toLong() and 0xFF) shl 8) or (b[0].toLong() and 0xFF)
                }
            }.getOrDefault(-1L)
        } else {
            -1L
        }
        return ArchiveIndex(
            entries = listOf(
                ArchiveEntryMeta(listOf(name), isDir = false, size = size, mtime = file.lastModified(), rawKey = name),
            ),
            encodingUsed = "UTF-8",
            encrypted = false,
        )
    }

    fun openTarStream(file: File, format: ArchiveFormat): TarArchiveInputStream {
        val fis = file.inputStream().buffered()
        val stream: InputStream = when (format) {
            ArchiveFormat.TAR -> fis
            ArchiveFormat.TAR_GZ ->
                org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(fis)
            ArchiveFormat.TAR_BZ2 ->
                org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(fis)
            ArchiveFormat.TAR_XZ ->
                org.apache.commons.compress.compressors.xz.XZCompressorInputStream(fis)
            ArchiveFormat.TAR_ZST ->
                org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream(fis)
            else -> throw IllegalArgumentException("not a tar: $format")
        }
        return TarArchiveInputStream(stream)
    }

    fun openSingleStream(file: File, format: ArchiveFormat): InputStream {
        val fis = file.inputStream().buffered()
        return when (format) {
            ArchiveFormat.GZ ->
                org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(fis)
            ArchiveFormat.BZ2 ->
                org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(fis)
            ArchiveFormat.XZ ->
                org.apache.commons.compress.compressors.xz.XZCompressorInputStream(fis)
            ArchiveFormat.ZST ->
                org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream(fis)
            else -> throw IllegalArgumentException("not a single compressor: $format")
        }
    }

    private fun splitName(name: String): List<String> {
        val segments = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        // ".." を含むエントリは途中打ち切りだと別エントリと衝突し得るため、丸ごと無視する（Zip Slip 対策）
        if (segments.any { it == ".." }) return emptyList()
        return segments
    }

    /** UTF-8 → Shift_JIS → CP437 の順で全サンプルが厳密デコードできる文字コードを選ぶ（SPEC §6.4） */
    private fun detectCharset(samples: List<ByteArray>): Charset {
        if (samples.isEmpty()) return Charsets.UTF_8
        for (name in listOf("UTF-8", "Shift_JIS", "CP437")) {
            val cs = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            val decoder = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val allOk = samples.all { raw ->
                runCatching { decoder.decode(ByteBuffer.wrap(raw)); true }.getOrDefault(false)
            }
            if (allOk) return cs
        }
        return Charset.forName("CP437")
    }
}
