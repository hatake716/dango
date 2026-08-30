package io.github.hatake716.dango.data.preview

import android.graphics.Bitmap
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import io.github.hatake716.dango.domain.model.FsPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.lang.SecurityException

sealed class PdfOpenResult {
    data class Opened(val holder: PdfDocumentHolder) : PdfOpenResult()
    /** パスワード付き。API 35+ ならパスワード入力で再試行できる（SPEC §6.5） */
    data class PasswordRequired(val canUnlock: Boolean) : PdfOpenResult()
    data class Failed(val message: String?) : PdfOpenResult()
}

/**
 * PdfRenderer のラッパー（SPEC §2: 追加依存なしの android.graphics.pdf）。
 * PdfRenderer はスレッドセーフではないため Mutex で直列化する。
 */
class PdfDocumentHolder private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) {
    private val mutex = Mutex()

    @Volatile
    private var closed = false

    val pageCount: Int get() = renderer.pageCount

    /** ページを幅 widthPx で描画する。アスペクト比はページに従う */
    suspend fun renderPage(index: Int, widthPx: Int): Bitmap = mutex.withLock {
        if (closed) throw IOException("closed")
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val scale = widthPx.toFloat() / page.width
                val heightPx = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }

    /** ページの縦横比（プレースホルダ用）。取得できなければ A4 相当 */
    suspend fun pageAspect(index: Int): Float = mutex.withLock {
        if (closed) return@withLock 1.414f
        withContext(Dispatchers.IO) {
            runCatching {
                renderer.openPage(index).use { it.height.toFloat() / it.width }
            }.getOrDefault(1.414f)
        }
    }

    /** 進行中の renderPage と競合しないよう、mutex を取ってから閉じる */
    fun close() {
        closed = true
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                runCatching { renderer.close() }
                runCatching { fd.close() }
            }
        }
    }

    companion object {
        suspend fun open(path: FsPath, password: String? = null): PdfOpenResult =
            withContext(Dispatchers.IO) {
                val file = File(path.displayPath())
                val fd = runCatching {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }.getOrElse { return@withContext PdfOpenResult.Failed(it.message) }
                try {
                    val renderer = if (password != null && Build.VERSION.SDK_INT >= 35) {
                        PdfRenderer(fd, LoadParams.Builder().setPassword(password).build())
                    } else {
                        PdfRenderer(fd)
                    }
                    PdfOpenResult.Opened(PdfDocumentHolder(fd, renderer))
                } catch (_: SecurityException) {
                    fd.close()
                    PdfOpenResult.PasswordRequired(canUnlock = Build.VERSION.SDK_INT >= 35)
                } catch (e: Exception) {
                    fd.close()
                    PdfOpenResult.Failed(e.message)
                }
            }
    }
}
