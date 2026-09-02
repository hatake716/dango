package io.github.hatake716.dango.ui.browser.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddToDrive
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.hatake716.dango.R

/**
 * クラウドストレージへのリンク（SPEC §15 #9）。
 * アプリ内で直接統合はせず、タップで公式アプリへ遷移する。
 * 未インストールなら Play ストア、それも開けなければ Web を開く。
 */
data class CloudLink(
    val id: String,
    @param:StringRes val labelRes: Int,
    val packageName: String,
    val webUrl: String,
    val icon: ImageVector,
)

// 表示はアルファベット順（追加時もこの順序を保つこと）
val CLOUD_LINKS: List<CloudLink> = listOf(
    CloudLink(
        id = "dropbox",
        labelRes = R.string.cloud_dropbox,
        packageName = "com.dropbox.android",
        webUrl = "https://www.dropbox.com",
        icon = Icons.Outlined.Cloud,
    ),
    CloudLink(
        id = "gdrive",
        labelRes = R.string.cloud_gdrive,
        packageName = "com.google.android.apps.docs",
        webUrl = "https://drive.google.com",
        icon = Icons.Outlined.AddToDrive,
    ),
    CloudLink(
        id = "gphotos",
        labelRes = R.string.cloud_gphotos,
        packageName = "com.google.android.apps.photos",
        webUrl = "https://photos.google.com",
        icon = Icons.Outlined.PhotoLibrary,
    ),
)

fun openCloudLink(context: Context, link: CloudLink) {
    // インストール済みなら公式アプリを起動
    context.packageManager.getLaunchIntentForPackage(link.packageName)?.let {
        context.startActivity(it)
        return
    }
    // 未インストールなら Play ストア → Web の順にフォールバック
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${link.packageName}")),
        )
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.webUrl)))
        }
    }
}
