package io.github.hatake716.dango.ui.quicklook

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import io.github.hatake716.dango.data.text.TextFileStore
import io.github.hatake716.dango.domain.model.EntryKind
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.ui.theme.DangoTheme

/**
 * Quick Look（SPEC §6.5）。フォルダ内のファイルを HorizontalPager で前後移動できる。
 * 出入りの拡大縮小と下スワイプは [QuickLookOverlay] / [LocalQuickLookMotion] が担う
 */
@Composable
fun QuickLookHost(
    files: List<FsEntry>,
    index: Int,
    textFileStore: TextFileStore,
    loadArchiveIndex: suspend (FsEntry) -> io.github.hatake716.dango.data.archive.ArchiveIndex,
    onIndexChange: (Int) -> Unit,
    onClose: () -> Unit,
    onShare: (FsEntry) -> Unit,
    onOpenWith: (FsEntry) -> Unit,
    onInfo: (FsEntry) -> Unit,
    onNotify: (Int) -> Unit,
    loadApkInfo: suspend (FsEntry) -> io.github.hatake716.dango.data.apk.ApkInfo,
    apkInstallState: io.github.hatake716.dango.data.apk.ApkInstallState,
    onInstallApk: (FsEntry, String?) -> Unit,
    onCancelApkInstall: () -> Unit,
    onLaunchApp: (String) -> Unit,
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
    val motion = LocalQuickLookMotion.current
    val density = LocalDensity.current
    val pageTop = WindowInsets.statusBars.getTop(density) + with(density) { QL_BAR_HEIGHT.roundToPx() }
    SideEffect { motion?.contentTop = pageTop.toFloat() }
    val dismiss = rememberQuickLookDismiss(
        motion = motion,
        enabled = !pagerLocked && !textEditing,
        onDismiss = { requestClose() },
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 暗幕は拡大に含めず、単独でフェードする（下スワイプの量でも薄くなる）
            .drawBehind {
                drawRect(Color.Black, alpha = QL_BACKDROP_ALPHA * (motion?.backdropAlpha() ?: 1f))
            },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .quickLookDismiss(dismiss)
                .quickLookContent(motion),
            userScrollEnabled = !pagerLocked && files.size > 1,
            key = { files[it].path.key },
        ) { page ->
            val entry = files[page]
            val isActive = page == pagerState.settledPage
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = QL_BAR_HEIGHT),
            ) {
                when (entry.kind) {
                    EntryKind.IMAGE -> ImagePage(
                        entry = entry,
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
                    EntryKind.ARCHIVE -> ArchivePage(
                        entry = entry,
                        loadIndex = loadArchiveIndex,
                    )
                    EntryKind.APK -> ApkPage(
                        entry = entry,
                        loadInfo = loadApkInfo,
                        installState = apkInstallState,
                        onInstall = onInstallApk,
                        onCancel = onCancelApkInstall,
                        onOpenApp = onLaunchApp,
                    )
                    else -> OtherPage(entry = entry, onOpenWith = onOpenWith)
                }
            }
        }

        // 上部バー（拡大には含めず、少し遅れてフェードインする）
        QuickLookTopBar(
            current = current,
            pageIndex = pagerState.currentPage,
            pageCount = files.size,
            onClose = { requestClose() },
            onOpenWith = { current?.let(onOpenWith) },
            onShare = { current?.let(onShare) },
            onInfo = { current?.let(onInfo) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .graphicsLayer { alpha = motion?.chromeAlpha() ?: 1f },
        )
    }
}
