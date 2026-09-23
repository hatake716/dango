package io.github.hatake716.dango.data.apk

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.createBitmap
import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.filePath
import coil3.request.Options
import coil3.size.pxOrElse
import java.io.IOException

/**
 * APK 自身のランチャーアイコンをサムネイルにする（Finder が .app をアイコンで見せるのと同じ）。
 * file:// の .apk だけを受け持つ。メモリキャッシュのキーは Coil の FileUriKeyer がパス＋更新日時で作る。
 * アイコンが無い APK は失敗させ、表示側は種類アイコンに戻す
 */
class ApkIconFetcher(
    private val path: String,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val pm = options.context.packageManager
        val pi = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, 0)
        }
        val ai = pi?.applicationInfo ?: throw IOException("cannot parse apk: $path")
        if (ai.icon == 0) throw IOException("apk has no icon: $path")
        ai.sourceDir = path
        ai.publicSourceDir = path
        val size = options.size
        val px = minOf(size.width.pxOrElse { DEFAULT_PX }, size.height.pxOrElse { DEFAULT_PX })
            .coerceIn(MIN_PX, MAX_PX)
        return ImageFetchResult(
            image = renderWithMargin(ai.loadIcon(pm), px).asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    /**
     * サムネイル枠いっぱいに描くと書類アイコンより一回り大きく見えるため、macOS のアプリアイコン
     * （キャンバスの約 8 割）と同じく周囲に余白を取って描く
     */
    private fun renderWithMargin(icon: Drawable, px: Int): Bitmap {
        val bitmap = createBitmap(px, px)
        val inset = (px * (1f - ICON_SCALE) / 2f).toInt()
        val old = Rect(icon.bounds)
        icon.setBounds(inset, inset, px - inset, px - inset)
        icon.draw(Canvas(bitmap))
        icon.bounds = old
        return bitmap
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "file") return null
            val path = data.filePath ?: return null
            if (!path.endsWith(".apk", ignoreCase = true)) return null
            return ApkIconFetcher(path, options)
        }
    }

    private companion object {
        const val DEFAULT_PX = 192
        const val MIN_PX = 16
        const val MAX_PX = 512
        const val ICON_SCALE = 0.84f
    }
}
