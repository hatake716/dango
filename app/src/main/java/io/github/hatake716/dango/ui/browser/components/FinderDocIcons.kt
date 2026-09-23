package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.hatake716.dango.domain.model.EntryKind
import io.github.hatake716.dango.domain.model.FsEntry

/*
 * macOS Finder 風の書類アイコン（SPEC §9「SF Symbols 風の線画をオリジナルで作成」）。
 * 白い用紙＋右上の折り返し角を共通の土台にし、種類ごとの装飾（文字行・写真・
 * フィルム・音符・ジッパー・Android）を重ねる。すべて独自作図で、色はベクター
 * 自身が持つ（Icon の tint は使わない。選択行でも白で潰さないのは Finder と同じ）
 */

private const val SHEET_L = 4.5f
private const val SHEET_R = 19.5f
private const val SHEET_T = 1.8f
private const val SHEET_B = 22.2f
private const val SHEET_RADIUS = 1.2f
private const val FOLD = 4.6f

private val SheetBorder = Color(0xFFC7C7CC)
private val LineGray = Color(0xFFBDBDC4)
private val DetailGray = Color(0xFF8E8E93)

private fun PathBuilder.sheetOutline(dy: Float = 0f) {
    moveTo(SHEET_L + SHEET_RADIUS, SHEET_T + dy)
    horizontalLineTo(SHEET_R - FOLD)
    lineTo(SHEET_R, SHEET_T + FOLD + dy)
    verticalLineTo(SHEET_B - SHEET_RADIUS + dy)
    quadTo(SHEET_R, SHEET_B + dy, SHEET_R - SHEET_RADIUS, SHEET_B + dy)
    horizontalLineTo(SHEET_L + SHEET_RADIUS)
    quadTo(SHEET_L, SHEET_B + dy, SHEET_L, SHEET_B - SHEET_RADIUS + dy)
    verticalLineTo(SHEET_T + SHEET_RADIUS + dy)
    quadTo(SHEET_L, SHEET_T + dy, SHEET_L + SHEET_RADIUS, SHEET_T + dy)
    close()
}

private fun PathBuilder.rect(l: Float, t: Float, r: Float, b: Float) {
    moveTo(l, t)
    horizontalLineTo(r)
    verticalLineTo(b)
    horizontalLineTo(l)
    close()
}

private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
    moveTo(l + rad, t)
    horizontalLineTo(r - rad)
    quadTo(r, t, r, t + rad)
    verticalLineTo(b - rad)
    quadTo(r, b, r - rad, b)
    horizontalLineTo(l + rad)
    quadTo(l, b, l, b - rad)
    verticalLineTo(t + rad)
    quadTo(l, t, l + rad, t)
    close()
}

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
    arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
    close()
}

private fun vGradient(top: Color, bottom: Color, y0: Float, y1: Float) = Brush.linearGradient(
    colorStops = arrayOf(0f to top, 1f to bottom),
    start = Offset(0f, y0),
    end = Offset(0f, y1),
)

/** 用紙（影・本体・折り返し角）を描いたうえで [decorate] で種類別の装飾を重ねる */
private fun docIcon(name: String, decorate: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // ごく薄い落ち影
        path(fill = SolidColor(Color.Black), fillAlpha = 0.07f) { sheetOutline(dy = 0.35f) }
        path(
            fill = vGradient(Color(0xFFFFFFFF), Color(0xFFF2F2F5), SHEET_T, SHEET_B),
            stroke = SolidColor(SheetBorder),
            strokeLineWidth = 0.45f,
            strokeLineJoin = StrokeJoin.Round,
        ) { sheetOutline() }
        // 折り返し角（めくれた三角）
        path(
            fill = Brush.linearGradient(
                colorStops = arrayOf(0f to Color(0xFFDCDCE2), 1f to Color(0xFFFFFFFF)),
                start = Offset(SHEET_R - FOLD, SHEET_T + FOLD),
                end = Offset(SHEET_R - FOLD / 2f, SHEET_T + FOLD / 2f),
            ),
            stroke = SolidColor(SheetBorder),
            strokeLineWidth = 0.45f,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(SHEET_R - FOLD, SHEET_T)
            verticalLineTo(SHEET_T + FOLD - 0.9f)
            quadTo(SHEET_R - FOLD, SHEET_T + FOLD, SHEET_R - FOLD + 0.9f, SHEET_T + FOLD)
            horizontalLineTo(SHEET_R)
            close()
        }
        decorate()
    }.build()

/** 文字の行（テキスト・PDF の本文表現） */
private fun ImageVector.Builder.textLines(count: Int, lastShort: Boolean = true) {
    path(fill = SolidColor(LineGray)) {
        for (i in 0 until count) {
            val y = 8.2f + i * 1.9f
            val right = if (lastShort && i == count - 1) 13.8f else 16.8f
            rect(7.2f, y, right, y + 0.75f)
        }
    }
}

val FinderDocGeneric: ImageVector by lazy { docIcon("FinderDocGeneric") {} }

val FinderDocText: ImageVector by lazy { docIcon("FinderDocText") { textLines(7) } }

val FinderDocPdf: ImageVector by lazy {
    docIcon("FinderDocPdf") {
        textLines(3, lastShort = false)
        path(fill = vGradient(Color(0xFFF0584D), Color(0xFFD93A30), 15.4f, 19.8f)) {
            roundRect(6.3f, 15.4f, 17.7f, 19.8f, 0.9f)
        }
    }
}

val FinderDocImage: ImageVector by lazy {
    docIcon("FinderDocImage") {
        // 空
        path(fill = vGradient(Color(0xFF8FD0FF), Color(0xFFD8F0FF), 8.4f, 17.2f)) {
            roundRect(6.4f, 8.4f, 17.6f, 17.2f, 0.8f)
        }
        // 太陽
        path(fill = SolidColor(Color(0xFFFFD23F))) { circle(14.8f, 10.8f, 1.2f) }
        // 山
        path(fill = vGradient(Color(0xFF5CC266), Color(0xFF3A9A46), 12.4f, 17.2f)) {
            moveTo(6.4f, 16.4f)
            lineTo(9.8f, 12.4f)
            lineTo(12.2f, 15.0f)
            lineTo(13.8f, 13.4f)
            lineTo(17.6f, 16.4f)
            quadTo(17.6f, 17.2f, 16.8f, 17.2f)
            horizontalLineTo(7.2f)
            quadTo(6.4f, 17.2f, 6.4f, 16.4f)
            close()
        }
    }
}

val FinderDocVideo: ImageVector by lazy {
    docIcon("FinderDocVideo") {
        path(fill = vGradient(Color(0xFFB46CF5), Color(0xFF8A43DC), 8.6f, 17.0f)) {
            roundRect(6.2f, 8.6f, 17.8f, 17.0f, 1.0f)
        }
        // フィルムの送り穴
        path(fill = SolidColor(Color.White), fillAlpha = 0.55f) {
            for (i in 0 until 6) {
                val x = 7.1f + i * 1.8f
                rect(x, 9.2f, x + 0.8f, 9.9f)
                rect(x, 15.7f, x + 0.8f, 16.4f)
            }
        }
        // 再生マーク
        path(fill = SolidColor(Color.White)) {
            moveTo(10.8f, 10.9f)
            lineTo(10.8f, 14.7f)
            lineTo(14.1f, 12.8f)
            close()
        }
    }
}

val FinderDocAudio: ImageVector by lazy {
    docIcon("FinderDocAudio") {
        path(fill = vGradient(Color(0xFFFF6482), Color(0xFFFF2D55), 8.6f, 17.6f)) {
            // 連桁
            moveTo(10.3f, 9.8f)
            lineTo(16.0f, 8.6f)
            lineTo(16.0f, 10.2f)
            lineTo(10.3f, 11.4f)
            close()
            // 符幹
            rect(10.3f, 10.4f, 11.0f, 16.2f)
            rect(15.3f, 9.2f, 16.0f, 15.0f)
            // 符頭
            circle(9.65f, 16.3f, 1.35f)
            circle(14.65f, 15.1f, 1.35f)
        }
    }
}

val FinderDocArchive: ImageVector by lazy {
    docIcon("FinderDocArchive") {
        // ジッパーの務歯（左右交互）
        path(fill = SolidColor(DetailGray)) {
            var i = 0
            var y = 2.3f
            while (y < 12.2f) {
                if (i % 2 == 0) rect(10.7f, y, 12.0f, y + 0.6f) else rect(12.0f, y, 13.3f, y + 0.6f)
                y += 0.95f
                i++
            }
        }
        // 引き手
        path(fill = vGradient(Color(0xFFB0B0B5), Color(0xFF8E8E93), 12.3f, 16.3f)) {
            roundRect(10.8f, 12.3f, 13.2f, 16.3f, 0.7f)
        }
        path(fill = SolidColor(Color(0xFFEDEDF0))) {
            roundRect(11.6f, 13.3f, 12.4f, 15.3f, 0.35f)
        }
    }
}

val FinderDocApk: ImageVector by lazy {
    docIcon("FinderDocApk") {
        val green = vGradient(Color(0xFF4BE08F), Color(0xFF2BB673), 10.0f, 14.2f)
        // 触角
        path(
            stroke = SolidColor(Color(0xFF34C77B)),
            strokeLineWidth = 0.55f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(9.9f, 11.4f)
            lineTo(9.0f, 9.9f)
            moveTo(14.1f, 11.4f)
            lineTo(15.0f, 9.9f)
        }
        // 頭（半円）
        path(fill = green) {
            moveTo(8.4f, 14.2f)
            arcTo(3.6f, 3.6f, 0f, false, true, 15.6f, 14.2f)
            close()
        }
        // 目
        path(fill = SolidColor(Color.White)) {
            circle(10.5f, 12.7f, 0.45f)
            circle(13.5f, 12.7f, 0.45f)
        }
    }
}

/** 種類ごとのアイコン。フォルダ以外は Finder 風の書類アイコン */
fun entryIcon(kind: EntryKind): ImageVector = when (kind) {
    EntryKind.FOLDER -> FinderFolder
    EntryKind.IMAGE -> FinderDocImage
    EntryKind.VIDEO -> FinderDocVideo
    EntryKind.AUDIO -> FinderDocAudio
    EntryKind.PDF -> FinderDocPdf
    EntryKind.TEXT -> FinderDocText
    EntryKind.ARCHIVE -> FinderDocArchive
    EntryKind.APK -> FinderDocApk
    EntryKind.OTHER -> FinderDocGeneric
}

/** 用紙上のラベル帯（24 単位の viewport 座標）と文字色 */
private data class LabelBand(val top: Float, val bottom: Float, val color: Color)

private fun labelBand(kind: EntryKind): LabelBand? = when (kind) {
    EntryKind.PDF -> LabelBand(15.4f, 19.8f, Color.White)
    EntryKind.OTHER -> LabelBand(14.6f, 18.6f, DetailGray)
    EntryKind.ARCHIVE -> LabelBand(17.0f, 20.6f, DetailGray)
    EntryKind.APK -> LabelBand(15.2f, 18.8f, DetailGray)
    else -> null
}

/** 拡張子ラベル（Finder の汎用書類アイコンに入る「XML」「ZIP」などの表記） */
private fun labelText(kind: EntryKind, name: String): String? {
    if (kind == EntryKind.PDF) return "PDF"
    if (kind == EntryKind.APK) return "APK"
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return null
    val ext = name.substring(dot + 1).uppercase()
    return ext.takeIf { it.length <= 5 }
}

/** ラベルを重ねるのはこのサイズ以上（小さいリスト行では読めないため装飾のみ） */
private val LABEL_MIN_SIZE = 40.dp

/**
 * 種類アイコンの描画。大きいサイズでは用紙に拡張子ラベルを重ねる（Finder 同様）。
 * 文字サイズはアイコンに比例させるため、端末のフォント倍率には追従させない
 */
@Composable
fun EntryKindIcon(
    kind: EntryKind,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(size)) {
        Image(
            imageVector = entryIcon(kind),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
        val band = labelBand(kind)
        val text = if (band != null && size >= LABEL_MIN_SIZE) labelText(kind, name) else null
        if (band != null && text != null) {
            val unit = size / 24f
            val fontSize = with(LocalDensity.current) { (unit * 2.6f).toSp() }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(unit * (band.bottom - band.top))
                    .offset(y = unit * band.top),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    color = band.color,
                    fontSize = fontSize,
                    lineHeight = fontSize,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * サムネイルがあれば表示し、無い・読み込めない（壊れた動画など）ときは種類アイコンに
 * 切り替える。失敗時に空白のまま残さないためのフォールバック。
 * [fit] = true なら Finder と同じく縦横比を保って枠内に収め、画像の実際の外形に
 * 細い縁と淡い影を付ける（正方形に切り抜かない）
 */
@Composable
fun EntryThumbnailOrIcon(
    entry: FsEntry,
    thumbSize: Dp,
    iconSize: Dp,
    shape: Shape,
    contentScale: ContentScale = ContentScale.Crop,
    fit: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var failed by remember(entry.previewUri) { mutableStateOf(false) }
    if (entry.previewUri != null && !failed) {
        if (fit) {
            var aspect by remember(entry.previewUri) { mutableStateOf<Float?>(null) }
            // アプリのアイコンは写真ではないので縁と影を付けない（Finder も同じ）
            val framed = entry.kind != EntryKind.APK
            Box(modifier = modifier.size(thumbSize), contentAlignment = Alignment.Center) {
                val a = aspect
                val frame = when {
                    a == null -> Modifier.size(thumbSize)
                    a >= 1f -> Modifier.size(thumbSize, thumbSize / a)
                    else -> Modifier.size(thumbSize * a, thumbSize)
                }
                AsyncImage(
                    model = entry.previewUri,
                    contentDescription = entry.name,
                    contentScale = ContentScale.Fit,
                    onSuccess = { state ->
                        val s = state.painter.intrinsicSize
                        if (s.isSpecified && s.width > 0f && s.height > 0f) aspect = s.width / s.height
                    },
                    onError = { failed = true },
                    modifier = frame
                        .then(
                            if (framed && a != null && thumbSize >= 32.dp) {
                                Modifier.shadow(1.dp, shape, clip = false)
                            } else {
                                Modifier
                            },
                        )
                        .clip(shape)
                        .then(
                            if (framed && a != null) Modifier.border(0.5.dp, ThumbBorder, shape) else Modifier,
                        ),
                )
            }
        } else {
            AsyncImage(
                model = entry.previewUri,
                contentDescription = entry.name,
                contentScale = contentScale,
                onError = { failed = true },
                modifier = modifier
                    .size(thumbSize)
                    .clip(shape),
            )
        }
    } else {
        EntryKindIcon(kind = entry.kind, name = entry.name, size = iconSize, modifier = modifier)
    }
}

private val ThumbBorder = Color(0x33000000)
