package io.github.hatake716.dango.data.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** バイナリ AndroidManifest.xml から読んだ最小限の情報 */
internal data class ApkManifest(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val targetSdk: Int,
    val testOnly: Boolean,
    val permissions: List<String>,
)

/**
 * PackageManager.getPackageArchiveInfo は minSdk が端末より新しい APK の解析自体を拒否して
 * null を返すため、その理由を画面に出すための予備の読み取り（バイナリ XML の直接解析）
 */
internal object ApkManifestReader {

    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML_RESOURCE_MAP = 0x0180
    private const val RES_XML_START_ELEMENT = 0x0102
    private const val UTF8_FLAG = 1 shl 8

    private const val ATTR_VERSION_CODE = 0x0101021b
    private const val ATTR_VERSION_NAME = 0x0101021c
    private const val ATTR_MIN_SDK = 0x0101020c
    private const val ATTR_TARGET_SDK = 0x01010270
    private const val ATTR_NAME = 0x01010003
    private const val ATTR_TEST_ONLY = 0x01010272

    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12

    /** マニフェストの上限サイズ。細工された APK が宣言サイズで巨大な確保をさせないため */
    private const val MAX_MANIFEST_BYTES = 4 shl 20

    /** 属性 1 件の最小バイト数（ResXMLTree_attribute） */
    private const val MIN_ATTR_SIZE = 20

    fun read(apkPath: String, platformSdk: Int): ApkManifest? = runCatching {
        val bytes = ZipFile(apkPath).use { zip ->
            val e = zip.getEntry("AndroidManifest.xml") ?: return null
            if (e.size !in 8L..MAX_MANIFEST_BYTES.toLong()) return null
            zip.getInputStream(e).use { input ->
                val out = java.io.ByteArrayOutputStream(e.size.toInt())
                val chunk = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > MAX_MANIFEST_BYTES) return null
                    out.write(chunk, 0, n)
                }
                out.toByteArray()
            }
        }
        parse(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), platformSdk)
    }.onFailure { android.util.Log.d("dango", "apk manifest read failed: $it") }.getOrNull()

    private class Attr(val name: String, val resId: Int, val type: Int, val data: Int, val raw: String?)

    private fun parse(buf: ByteBuffer, platformSdk: Int): ApkManifest? {
        var strings: List<String> = emptyList()
        var resIds = IntArray(0)
        var pkg: String? = null
        var versionCode = 0L
        var versionName: String? = null
        var minSdk = 1
        var targetSdk = 0
        var testOnly = false
        val permissions = mutableListOf<String>()

        fun str(i: Int) = strings.getOrNull(i)

        var pos = buf.getShort(2).toInt() and 0xFFFF
        while (pos + 8 <= buf.limit()) {
            val chunkType = buf.getShort(pos).toInt() and 0xFFFF
            val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val size = buf.getInt(pos + 4)
            // 壊れた・細工されたチャンクはそこで打ち切る（範囲外の読み取りや巨大な確保をしない）
            if (headerSize < 8 || size < headerSize || pos.toLong() + size > buf.limit()) break
            when (chunkType) {
                RES_STRING_POOL -> strings = readStringPool(buf, pos, size)
                RES_XML_RESOURCE_MAP -> resIds = IntArray((size - headerSize) / 4) {
                    buf.getInt(pos + headerSize + it * 4)
                }
                RES_XML_START_ELEMENT -> {
                    val ext = pos + headerSize
                    if (ext + 20 > pos + size) {
                        pos += size
                        continue
                    }
                    val tag = str(buf.getInt(ext + 4)) ?: ""
                    val attrStart = buf.getShort(ext + 8).toInt() and 0xFFFF
                    val attrSize = buf.getShort(ext + 10).toInt() and 0xFFFF
                    val attrCount = buf.getShort(ext + 12).toInt() and 0xFFFF
                    val wanted = tag == "manifest" || tag == "uses-sdk" || tag == "application" ||
                        tag == "uses-permission" || tag == "uses-permission-sdk-23"
                    val fits = attrSize >= MIN_ATTR_SIZE &&
                        attrStart.toLong() + attrCount.toLong() * attrSize <= (size - headerSize).toLong()
                    if (!wanted || !fits) {
                        pos += size
                        continue
                    }
                    val attrs = (0 until attrCount).map { i ->
                        val a = ext + attrStart + i * attrSize
                        val nameIdx = buf.getInt(a + 4)
                        Attr(
                            name = str(nameIdx) ?: "",
                            resId = resIds.getOrElse(nameIdx) { 0 },
                            type = buf.get(a + 15).toInt() and 0xFF,
                            data = buf.getInt(a + 16),
                            raw = str(buf.getInt(a + 8)),
                        )
                    }
                    fun find(resId: Int, name: String) =
                        attrs.firstOrNull { it.resId == resId } ?: attrs.firstOrNull { it.name == name }
                    fun Attr.int(): Int? = when (this.type) {
                        TYPE_INT_DEC, TYPE_INT_HEX -> this.data
                        // プレビュー版のコード名指定（"Baklava" 等）は正式版の端末では常に新しすぎる
                        TYPE_STRING -> (str(this.data) ?: this.raw)?.toIntOrNull() ?: (platformSdk + 1)
                        else -> null
                    }
                    when (tag) {
                        "manifest" -> {
                            pkg = attrs.firstOrNull { it.name == "package" }?.let { str(it.data) ?: it.raw }
                            find(ATTR_VERSION_CODE, "versionCode")?.int()?.let { versionCode = it.toLong() and 0xFFFFFFFFL }
                            versionName = find(ATTR_VERSION_NAME, "versionName")?.let { a ->
                                if (a.type == TYPE_STRING) str(a.data) ?: a.raw else a.raw
                            }
                        }
                        "uses-sdk" -> {
                            find(ATTR_MIN_SDK, "minSdkVersion")?.int()?.let { minSdk = it }
                            find(ATTR_TARGET_SDK, "targetSdkVersion")?.int()?.let { targetSdk = it }
                        }
                        "application" ->
                            testOnly = find(ATTR_TEST_ONLY, "testOnly")
                                ?.let { it.type == TYPE_INT_BOOLEAN && it.data != 0 } == true
                        "uses-permission", "uses-permission-sdk-23" ->
                            find(ATTR_NAME, "name")?.let { a -> (if (a.type == TYPE_STRING) str(a.data) else a.raw) }
                                ?.let(permissions::add)
                    }
                }
            }
            pos += size
        }
        val name = pkg ?: return null
        return ApkManifest(
            packageName = name,
            versionCode = versionCode,
            versionName = versionName,
            minSdk = minSdk,
            targetSdk = if (targetSdk == 0) minSdk else targetSdk,
            testOnly = testOnly,
            permissions = permissions.distinct(),
        )
    }

    private fun readStringPool(buf: ByteBuffer, chunk: Int, chunkSize: Int): List<String> {
        val headerSize = buf.getShort(chunk + 2).toInt() and 0xFFFF
        if (headerSize < 28) return emptyList()
        val count = buf.getInt(chunk + 8)
        if (count < 0 || headerSize + count.toLong() * 4 > chunkSize) return emptyList()
        val utf8 = buf.getInt(chunk + 16) and UTF8_FLAG != 0
        val stringsStart = chunk + buf.getInt(chunk + 20)
        val end = chunk + chunkSize
        return List(count) { i ->
            val p = stringsStart + buf.getInt(chunk + headerSize + i * 4)
            if (p < chunk || p >= end) {
                ""
            } else if (utf8) {
                readUtf8(buf, p, end)
            } else {
                readUtf16(buf, p, end)
            }
        }
    }

    private fun readUtf8(buf: ByteBuffer, start: Int, end: Int): String {
        // 先頭は文字数（1〜2 バイト）、続いてバイト長（1〜2 バイト）
        var p = start + if (buf.get(start).toInt() and 0x80 != 0) 2 else 1
        val b0 = buf.get(p).toInt() and 0xFF
        val len: Int
        if (b0 and 0x80 != 0) {
            len = ((b0 and 0x7F) shl 8) or (buf.get(p + 1).toInt() and 0xFF)
            p += 2
        } else {
            len = b0
            p += 1
        }
        if (p + len > end) return ""
        return String(ByteArray(len) { buf.get(p + it) }, Charsets.UTF_8)
    }

    private fun readUtf16(buf: ByteBuffer, start: Int, end: Int): String {
        val u0 = buf.getShort(start).toInt() and 0xFFFF
        val len: Int
        val p: Int
        if (u0 and 0x8000 != 0) {
            len = ((u0 and 0x7FFF) shl 16) or (buf.getShort(start + 2).toInt() and 0xFFFF)
            p = start + 4
        } else {
            len = u0
            p = start + 2
        }
        if (len < 0 || p.toLong() + len.toLong() * 2 > end) return ""
        return String(CharArray(len) { buf.getShort(p + it * 2).toInt().toChar() })
    }
}
