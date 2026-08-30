package io.github.hatake716.dango.ui.util

import io.github.hatake716.dango.domain.model.EntryKind
import io.github.hatake716.dango.domain.model.FsEntry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Finder 同様の 1000 進サイズ表記（48.2 GB など） */
fun formatSize(bytes: Long): String {
    if (bytes < 0) return "–"
    if (bytes < 1000) return "$bytes バイト"
    var value = bytes.toDouble()
    val units = listOf("KB", "MB", "GB", "TB")
    var unit = ""
    for (u in units) {
        value /= 1000.0
        unit = u
        if (value < 1000) break
    }
    return if (value >= 100) {
        "%.0f %s".format(Locale.JAPAN, value, unit)
    } else {
        "%.1f %s".format(Locale.JAPAN, value, unit)
    }
}

private val timeFormat = SimpleDateFormat("H:mm", Locale.JAPAN)
private val dateTimeFormat = SimpleDateFormat("yyyy/MM/dd H:mm", Locale.JAPAN)

/** Finder 風の相対日付（今日 18:23 / 昨日 9:10 / 2026/08/30 18:23） */
fun formatDateTime(millis: Long): String {
    if (millis <= 0) return "–"
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return "今日 " + timeFormat.format(Date(millis))
    now.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    if (yesterday) return "昨日 " + timeFormat.format(Date(millis))
    return dateTimeFormat.format(Date(millis))
}

/** Finder の「種類」列相当のラベル（多言語化は M6 で対応） */
fun kindLabel(entry: FsEntry): String {
    if (entry.isDir) return "フォルダ"
    val ext = entry.extension.uppercase(Locale.ROOT)
    return when (entry.kind) {
        EntryKind.IMAGE -> "$ext 画像"
        EntryKind.VIDEO -> "$ext 動画"
        EntryKind.AUDIO -> "$ext オーディオ"
        EntryKind.PDF -> "PDF 書類"
        EntryKind.TEXT -> when (entry.extension) {
            "txt" -> "標準テキスト書類"
            "md" -> "Markdown 書類"
            else -> "$ext テキスト"
        }
        EntryKind.ARCHIVE -> "$ext アーカイブ"
        EntryKind.APK -> "Android アプリ"
        EntryKind.FOLDER -> "フォルダ"
        EntryKind.OTHER -> if (ext.isEmpty()) "書類" else "$ext ファイル"
    }
}
