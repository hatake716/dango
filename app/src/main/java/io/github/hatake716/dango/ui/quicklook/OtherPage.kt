package io.github.hatake716.dango.ui.quicklook

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.ui.browser.components.entryIcon
import io.github.hatake716.dango.ui.theme.DarkDangoColors
import io.github.hatake716.dango.ui.browser.components.entryTint
import io.github.hatake716.dango.ui.util.formatSize
import io.github.hatake716.dango.ui.util.kindLabel

/** 未対応形式のフォールバック（SPEC §6.5: アイコン＋メタ情報＋別のアプリで開く） */
@Composable
fun OtherPage(
    entry: FsEntry,
    onOpenWith: (FsEntry) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(
            imageVector = entryIcon(entry.kind),
            contentDescription = null,
            tint = entryTint(entry.kind, DarkDangoColors),
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = entry.name,
            color = Color.White,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = kindLabel(entry) + " · " + formatSize(entry.size),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = { onOpenWith(entry) }) {
            Text(stringResource(R.string.ql_open_with))
        }
    }
}
