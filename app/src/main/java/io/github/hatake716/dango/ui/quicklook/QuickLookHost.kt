package io.github.hatake716.dango.ui.quicklook

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.text.TextFileStore
import io.github.hatake716.dango.domain.model.EntryKind
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.ui.theme.DangoTheme

/**
 * Quick Look（SPEC §6.5）。フォルダ内のファイルを HorizontalPager で前後移動できる。
 * 共有要素トランジションは近似（スケール＋フェード）。真の SharedTransition は M6 で。
 */
@Composable
fun QuickLookHost(
    files: List<FsEntry>,
    index: Int,
    textFileStore: TextFileStore,
    onIndexChange: (Int) -> Unit,
    onClose: () -> Unit,
    onShare: (FsEntry) -> Unit,
    onOpenWith: (FsEntry) -> Unit,
    onInfo: (FsEntry) -> Unit,
    onNotify: (Int) -> Unit,
) {
    val colors = DangoTheme.colors
    val pagerState = rememberPagerState(initialPage = index) { files.size }
    var pagerLocked by remember { mutableStateOf(false) }
    var textEditing by remember { mutableStateOf(false) }
    var closeSignal by remember { mutableStateOf(0) }

    LaunchedEffect(pagerState.settledPage) {
        onIndexChange(pagerState.settledPage)
    }

    BackHandler { onClose() }

    // 編集中は未保存確認を経由してから閉じる（SPEC §6.5.1）
    fun requestClose() {
        if (textEditing) closeSignal++ else onClose()
    }

    val current = files.getOrNull(pagerState.currentPage)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f)),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !pagerLocked && files.size > 1,
            key = { files[it].path.key },
        ) { page ->
            val entry = files[page]
            val isActive = page == pagerState.settledPage
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 44.dp),
            ) {
                when (entry.kind) {
                    EntryKind.IMAGE -> ImagePage(
                        entry = entry,
                        onDismiss = onClose,
                        onZoomChanged = { zoomed -> if (isActive) pagerLocked = zoomed },
                    )
                    EntryKind.VIDEO, EntryKind.AUDIO -> MediaPage(
                        entry = entry,
                        isActive = isActive,
                    )
                    EntryKind.PDF -> PdfPage(
                        entry = entry,
                        onZoomChanged = { zoomed -> if (isActive) pagerLocked = zoomed },
                    )
                    EntryKind.TEXT -> TextPage(
                        entry = entry,
                        textFileStore = textFileStore,
                        onNotify = onNotify,
                        onEditingChanged = { editing ->
                            if (isActive) {
                                pagerLocked = editing
                                textEditing = editing
                            }
                        },
                        closeSignal = if (isActive) closeSignal else 0,
                        onHostClose = onClose,
                    )
                    else -> OtherPage(entry = entry, onOpenWith = onOpenWith)
                }
            }
        }

        // 上部バー
        Column(modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(44.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { requestClose() }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.ql_close),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = current?.name ?: "",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                )
                if (files.size > 1) {
                    Text(
                        text = stringResource(
                            R.string.ql_page_of,
                            pagerState.currentPage + 1,
                            files.size,
                        ),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                IconButton(onClick = { current?.let(onShare) }) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = stringResource(R.string.ql_share),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = { current?.let(onOpenWith) }) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = stringResource(R.string.ql_open_with),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = { current?.let(onInfo) }) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.ql_info),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
