package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.ui.theme.DangoTheme
import io.github.hatake716.dango.ui.util.formatSize

/** ステータスバー（SPEC §4.5: N項目, 空き容量。選択時は選択数を表示） */
@Composable
fun StatusBar(
    itemCount: Int,
    selectedCount: Int,
    freeSpaceBytes: Long?,
) {
    val colors = DangoTheme.colors
    val countText = if (selectedCount > 0) {
        stringResource(R.string.status_selected, selectedCount, itemCount)
    } else {
        stringResource(R.string.status_items, itemCount)
    }
    val freeText = freeSpaceBytes?.let { "、" + stringResource(R.string.status_free, formatSize(it)) } ?: ""
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.toolbar)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = countText + freeText,
            color = colors.textSecondary,
            fontSize = 11.sp,
        )
    }
}
