package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.combinedClickable
import io.github.hatake716.dango.R
import io.github.hatake716.dango.domain.model.FsPath
import io.github.hatake716.dango.ui.theme.DangoTheme

/** パスバー（SPEC §4.5）。内部ストレージ配下は「内部ストレージ › ...」と表示する */
@Composable
fun PathBar(
    currentPath: FsPath,
    internalRoot: FsPath,
    onNavigate: (FsPath) -> Unit,
    onPathCopied: () -> Unit,
) {
    val colors = DangoTheme.colors
    val clipboard = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    val crumbs: List<Pair<String, FsPath>> = buildCrumbs(
        currentPath = currentPath,
        internalRoot = internalRoot,
        internalLabel = stringResource(R.string.loc_internal),
    )

    LaunchedEffect(currentPath) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(colors.toolbar)
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboard.setText(AnnotatedString(currentPath.displayPath()))
                    onPathCopied()
                },
            )
            .horizontalScroll(scrollState)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, (label, path) ->
            val isLast = index == crumbs.lastIndex
            Text(
                text = label,
                color = if (isLast) colors.textPrimary else colors.textSecondary,
                fontSize = 12.sp,
                fontWeight = if (isLast) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = !isLast) { onNavigate(path) }
                    .padding(horizontal = 3.dp, vertical = 2.dp),
            )
            if (!isLast) {
                Text(
                    text = "›",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
    }
}

private fun buildCrumbs(
    currentPath: FsPath,
    internalRoot: FsPath,
    internalLabel: String,
): List<Pair<String, FsPath>> {
    val crumbs = mutableListOf<Pair<String, FsPath>>()
    if (currentPath.isDescendantOf(internalRoot)) {
        crumbs.add(internalLabel to internalRoot)
        var path = internalRoot
        for (segment in currentPath.segments.drop(internalRoot.segments.size)) {
            path = path.child(segment)
            crumbs.add(segment to path)
        }
    } else {
        var path = FsPath(currentPath.scheme, emptyList())
        crumbs.add("/" to path)
        for (segment in currentPath.segments) {
            path = path.child(segment)
            crumbs.add(segment to path)
        }
    }
    return crumbs
}
