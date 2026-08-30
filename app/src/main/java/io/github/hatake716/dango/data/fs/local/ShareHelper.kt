package io.github.hatake716.dango.data.fs.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import io.github.hatake716.dango.domain.model.FsEntry
import java.io.File

/** 共有 / 別のアプリで開く（SPEC §6.3: FileProvider 経由）。UI が File を触らないための橋渡し */
object ShareHelper {

    private fun authority(context: Context) = "${context.packageName}.fileprovider"

    private fun contentUri(context: Context, entry: FsEntry): Uri =
        FileProvider.getUriForFile(context, authority(context), File(entry.path.displayPath()))

    fun mimeType(entry: FsEntry): String {
        val ext = entry.extension
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** ACTION_SEND / ACTION_SEND_MULTIPLE の chooser Intent を作る */
    fun shareIntent(context: Context, entries: List<FsEntry>): Intent {
        val files = entries.filter { !it.isDir }
        val uris = ArrayList(files.map { contentUri(context, it) })
        val types = files.map { mimeType(it) }.distinct()
        val type = if (types.size == 1) types.first() else "*/*"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = type
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }

    /** ACTION_VIEW の chooser Intent を作る */
    fun openWithIntent(context: Context, entry: FsEntry): Intent {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri(context, entry), mimeType(entry))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(view, null)
    }
}
