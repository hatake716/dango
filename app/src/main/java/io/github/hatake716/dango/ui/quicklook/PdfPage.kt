package io.github.hatake716.dango.ui.quicklook

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.preview.PdfDocumentHolder
import io.github.hatake716.dango.data.preview.PdfOpenResult
import io.github.hatake716.dango.domain.model.FsEntry

/** PDF プレビュー（SPEC §6.5: 縦スクロール・ズーム・ページ表示・パスワード付き対応） */
@Composable
fun PdfPage(
    entry: FsEntry,
    onZoomChanged: (Boolean) -> Unit,
) {
    var password by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var cancelled by remember { mutableStateOf(false) }
    var openResult by remember { mutableStateOf<PdfOpenResult?>(null) }

    LaunchedEffect(entry.path.key, password, attempt) {
        openResult = PdfDocumentHolder.open(entry.path, password)
    }
    DisposableEffect(openResult) {
        val holder = (openResult as? PdfOpenResult.Opened)?.holder
        onDispose { holder?.close() }
    }

    when (val result = openResult) {
        null -> Loading()
        is PdfOpenResult.Failed -> Message(stringResource(R.string.ql_load_error))
        is PdfOpenResult.PasswordRequired -> {
            when {
                !result.canUnlock -> Message(stringResource(R.string.ql_pdf_unsupported))
                cancelled -> Message(stringResource(R.string.ql_pdf_password_title))
                else -> PasswordDialog(
                    wrongPassword = attempt > 0,
                    onSubmit = {
                        password = it
                        attempt++
                    },
                    onCancel = { cancelled = true },
                )
            }
        }
        is PdfOpenResult.Opened -> PdfContent(result.holder, onZoomChanged)
    }
}

@Composable
private fun PdfContent(
    holder: PdfDocumentHolder,
    onZoomChanged: (Boolean) -> Unit,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()

    LaunchedEffect(zoom) {
        onZoomChanged(zoom > 1.02f)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val baseWidthPx = with(density) { maxWidth.toPx() }.toInt()
        val pageWidthDp = maxWidth * zoom
        // ピンチ中のフレーム毎再レンダリングを避けるため、描画解像度は段階量子化する
        val renderBucket = when {
            zoom <= 1f -> 1f
            zoom <= 1.5f -> 1.5f
            zoom <= 2f -> 2f
            else -> 3f
        }
        val renderWidthPx = (baseWidthPx * renderBucket).toInt().coerceAtMost(2048)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.size >= 2) {
                                val z = event.calculateZoom()
                                if (z != 1f) {
                                    zoom = (zoom * z).coerceIn(1f, 3f)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .then(if (zoom > 1f) Modifier.horizontalScroll(hScroll) else Modifier),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.width(pageWidthDp),
            ) {
                items(holder.pageCount) { index ->
                    PdfPageImage(holder, index, renderWidthPx, pageWidthDp.value)
                }
            }
        }

        // ページ番号（SPEC §6.5）
        val pageLabel by remember {
            derivedStateOf { listState.firstVisibleItemIndex + 1 }
        }
        Text(
            text = stringResource(R.string.ql_page_of, pageLabel, holder.pageCount),
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PdfPageImage(
    holder: PdfDocumentHolder,
    index: Int,
    renderWidthPx: Int,
    pageWidthDp: Float,
) {
    // 解像度変更時も前のビットマップを表示し続け、白紙に戻さない
    var bitmap by remember(holder, index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(holder, index, renderWidthPx) {
        runCatching { holder.renderPage(index, renderWidthPx) }
            .onSuccess { bitmap = it }
    }
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.width(pageWidthDp.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .width(pageWidthDp.dp)
                    .aspectRatio(1f / 1.414f)
                    .background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun PasswordDialog(
    wrongPassword: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.ql_pdf_password_title)) },
        text = {
            Column {
                Text(
                    if (wrongPassword) {
                        stringResource(R.string.ql_pdf_wrong_password)
                    } else {
                        stringResource(R.string.ql_pdf_password_body)
                    },
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.ql_pdf_password_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(value) }) {
                Text(stringResource(R.string.ql_pdf_unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f))
    }
}

@Composable
private fun Message(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
    }
}
