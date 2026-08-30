package io.github.hatake716.dango.ui.info

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.info.EntryDetails
import io.github.hatake716.dango.data.info.InfoLoader
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.ui.browser.components.entryIcon
import io.github.hatake716.dango.ui.browser.components.entryTint
import io.github.hatake716.dango.ui.theme.DangoTheme
import io.github.hatake716.dango.ui.util.formatDateTime
import io.github.hatake716.dango.ui.util.formatSize
import io.github.hatake716.dango.ui.util.kindLabel

/** 情報ウィンドウ（SPEC §6.3「情報を見る」。タグ・コメントは M5） */
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.windowBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = entryIcon(entry.kind),
                    contentDescription = null,
                    tint = entryTint(entry.kind, colors),
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = entry.name,
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = kindLabel(entry),
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = colors.divider)
            Spacer(Modifier.height(8.dp))

            val sizeText = if (entry.isDir) {
                dirSize?.let {
                    formatSize(it.first) + "（" + stringResource(R.string.info_items, it.second) + "）"
                } ?: stringResource(R.string.info_calculating)
            } else {
                formatSize(entry.size)
            }
            InfoRow(stringResource(R.string.info_size), sizeText)
            InfoRow(
                stringResource(R.string.info_location),
                entry.path.parent?.displayPath() ?: entry.path.displayPath(),
            )
            details?.createdAt?.let { InfoRow(stringResource(R.string.info_created), formatDateTime(it)) }
            InfoRow(stringResource(R.string.info_modified), formatDateTime(entry.lastModified))
            details?.let { InfoRow(stringResource(R.string.info_permissions), it.permissions) }

            if (!entry.isDir) {
                HashRow(stringResource(R.string.info_hash_md5), md5, computeMd5) {
                    computeMd5 = true
                }
                HashRow(stringResource(R.string.info_hash_sha256), sha256, computeSha256) {
                    computeSha256 = true
                }
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

            details?.extras?.takeIf { it.isNotEmpty() }?.let { extras ->
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = colors.divider)
                Spacer(Modifier.height(8.dp))
                extras.forEach { (label, value) -> InfoRow(label, value) }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = DangoTheme.colors
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = label,
            color = colors.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier.width(110.dp),
        )
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
    Row(
        modifier = Modifier.padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colors.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier.width(110.dp),
        )
        if (value.isNullOrEmpty()) {
            TextButton(onClick = onCompute, enabled = !computing) {
                Text(
                    text = if (computing) {
                        stringResource(R.string.info_calculating)
                    } else {
                        stringResource(R.string.info_compute)
                    },
                    fontSize = 11.sp,
                )
            }
        } else {
            Text(
                text = value,
                color = colors.textPrimary,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
