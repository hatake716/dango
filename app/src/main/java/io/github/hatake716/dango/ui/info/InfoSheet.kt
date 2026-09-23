package io.github.hatake716.dango.ui.info

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.info.EntryDetails
import io.github.hatake716.dango.data.info.InfoLoader
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.ui.browser.components.EntryThumbnailOrIcon
import io.github.hatake716.dango.ui.browser.components.FinderPushButton
import io.github.hatake716.dango.ui.browser.components.FinderSheetHandle
import io.github.hatake716.dango.ui.theme.DangoMotion
import io.github.hatake716.dango.ui.theme.DangoTheme
import io.github.hatake716.dango.ui.util.formatDateTime
import io.github.hatake716.dango.ui.util.formatSize
import io.github.hatake716.dango.ui.util.kindLabel

private val SheetShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
private val LABEL_WIDTH = 96.dp

/**
 * 情報ウインドウ（SPEC §6.3「情報を見る」。タグ・コメントは M5）。
 * Finder の「情報を見る」と同じく、見出しにアイコン・名前・サイズ・変更日を置き、
 * 下に開閉できるセクション（一般情報 / 詳細情報）を並べる
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(
    entry: FsEntry,
    infoLoader: InfoLoader,
    onDismiss: () -> Unit,
) {
    val colors = DangoTheme.colors
    var details by remember { mutableStateOf<EntryDetails?>(null) }
    var dirSize by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var md5 by remember { mutableStateOf<String?>(null) }
    var sha256 by remember { mutableStateOf<String?>(null) }
    var computeMd5 by remember { mutableStateOf(false) }
    var computeSha256 by remember { mutableStateOf(false) }

    LaunchedEffect(entry.path.key) {
        details = runCatching { infoLoader.load(entry) }.getOrNull()
    }
    LaunchedEffect(entry.path.key) {
        if (entry.isDir) {
            // フォルダサイズは非同期集計（SPEC §6.3）
            runCatching {
                infoLoader.folderSize(entry.path).collect { dirSize = it }
            }
        }
    }
    if (!entry.isDir) {
        // セクションを畳んでも計算が止まらないよう、行の外で待つ
        LaunchedEffect(computeMd5) {
            if (computeMd5) {
                md5 = runCatching { infoLoader.hash(entry.path, "MD5") }.getOrNull()
                computeMd5 = false
            }
        }
        LaunchedEffect(computeSha256) {
            if (computeSha256) {
                sha256 = runCatching { infoLoader.hash(entry.path, "SHA-256") }.getOrNull()
                computeSha256 = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = SheetShape,
        containerColor = colors.toolbar,
        dragHandle = { FinderSheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
        ) {
            val calculating = stringResource(R.string.info_calculating)
            Row(verticalAlignment = Alignment.CenterVertically) {
                EntryThumbnailOrIcon(
                    entry = entry,
                    thumbSize = 56.dp,
                    iconSize = 56.dp,
                    shape = RoundedCornerShape(6.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row {
                        Text(
                            text = entry.name,
                            color = colors.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (entry.isDir) {
                                dirSize?.let { formatSize(it.first) } ?: calculating
                            } else {
                                formatSize(entry.size)
                            },
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.info_header_modified, formatDateTime(entry.lastModified)),
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            InfoDivider()

            InfoSection(stringResource(R.string.info_section_general)) {
                val sizeText = if (entry.isDir) {
                    dirSize?.let {
                        formatSize(it.first) + "（" + stringResource(R.string.info_items, it.second) + "）"
                    } ?: calculating
                } else {
                    formatSize(entry.size)
                }
                InfoRow(stringResource(R.string.info_kind), kindLabel(entry))
                InfoRow(stringResource(R.string.info_size), sizeText)
                InfoRow(
                    stringResource(R.string.info_location),
                    entry.path.parent?.displayPath() ?: entry.path.displayPath(),
                )
                details?.createdAt?.let { InfoRow(stringResource(R.string.info_created), formatDateTime(it)) }
                InfoRow(stringResource(R.string.info_modified), formatDateTime(entry.lastModified))
                details?.let { InfoRow(stringResource(R.string.info_permissions), it.permissions) }
            }

            val extras = details?.extras.orEmpty()
            if (!entry.isDir || extras.isNotEmpty()) {
                InfoDivider()
                InfoSection(stringResource(R.string.info_section_more)) {
                    extras.forEach { (label, value) -> InfoRow(label, value) }
                    if (!entry.isDir) {
                        HashRow(stringResource(R.string.info_hash_md5), md5, computeMd5) {
                            computeMd5 = true
                        }
                        HashRow(stringResource(R.string.info_hash_sha256), sha256, computeSha256) {
                            computeSha256 = true
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoDivider() {
    HorizontalDivider(thickness = 0.5.dp, color = DangoTheme.colors.divider)
}

/** 開閉できるセクション（Finder の「情報を見る」の ▸ 見出し） */
@Composable
private fun InfoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = DangoTheme.colors
    var expanded by rememberSaveable(title) { mutableStateOf(true) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = DangoMotion.expand(),
        label = "infoChevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable { expanded = !expanded },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier
                .size(16.dp)
                .rotate(rotation),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = title,
            color = colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(DangoMotion.expand()) + fadeIn(DangoMotion.expand()),
        exit = shrinkVertically(DangoMotion.expand()) + fadeOut(DangoMotion.expand()),
    ) {
        Column(modifier = Modifier.padding(bottom = 8.dp), content = content)
    }
}

/** ラベルは右揃えの太字＋「：」、値は通常の太さ（Finder の「情報を見る」と同じ組み方） */
@Composable
private fun InfoRow(label: String, value: String) {
    val colors = DangoTheme.colors
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = stringResource(R.string.info_label_colon, label),
            color = colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(LABEL_WIDTH),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            color = colors.textPrimary,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HashRow(
    label: String,
    value: String?,
    computing: Boolean,
    onCompute: () -> Unit,
) {
    val colors = DangoTheme.colors
    val hasValue = !value.isNullOrEmpty()
    Row(
        modifier = Modifier.padding(vertical = if (hasValue) 2.dp else 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.info_label_colon, label),
            color = colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(LABEL_WIDTH)
                .then(if (hasValue) Modifier.alignByBaseline() else Modifier),
        )
        Spacer(Modifier.width(8.dp))
        if (!hasValue) {
            FinderPushButton(
                text = if (computing) {
                    stringResource(R.string.info_calculating)
                } else {
                    stringResource(R.string.info_compute)
                },
                onClick = onCompute,
                enabled = !computing,
                compact = true,
                touchPadding = 3.dp,
            )
        } else {
            Text(
                text = value.orEmpty(),
                color = colors.textPrimary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .weight(1f)
                    .alignByBaseline(),
            )
        }
    }
}
