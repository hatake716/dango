package io.github.hatake716.dango.ui.browser.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.combinedClickable
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.net.NetPaths
import io.github.hatake716.dango.domain.model.FsPath
import io.github.hatake716.dango.ui.theme.DangoMotion
import io.github.hatake716.dango.ui.theme.DangoTheme

/** パスバー（SPEC §4.5）。内部ストレージ配下は「内部ストレージ › ...」と表示する */
@Composable
fun PathBar(
    currentPath: FsPath,
    internalRoot: FsPath,
    onNavigate: (FsPath) -> Unit,
    onPathCopied: () -> Unit,
    /** パスバーへのドロップでその階層に移動（SPEC §4.5）。null なら受け付けない */
    onDropKeys: ((FsPath, Set<String>) -> Unit)? = null,
    /** ネットワーク接続のルートラベル解決（接続 ID → 表示名） */
    connectionLabel: (Long) -> String? = { null },
) {
    val colors = DangoTheme.colors
    val clipboard = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    val crumbs: List<Pair<String, FsPath>> = when {
        currentPath.scheme == "trash" ->
            listOf(stringResource(R.string.loc_trash) to currentPath)
        currentPath.scheme == "tag" ->
            listOf(
                stringResource(
                    tagLabelRes(currentPath.segments.firstOrNull() ?: "gray"),
                ) to currentPath,
            )
        NetPaths.isNetwork(currentPath) -> {
            // 接続名をルートとして、リモートパスの各階層をたどれるようにする
            val connId = NetPaths.connectionId(currentPath)
            val rootLabel = connectionLabel(connId) ?: currentPath.scheme
            val root = FsPath(currentPath.scheme, listOf(connId.toString()))
            buildList {
                add(rootLabel to root)
                var p = root
                for (segment in NetPaths.remoteSegments(currentPath)) {
                    p = p.child(segment)
                    add(segment to p)
                }
            }
        }
        else -> buildCrumbs(
            currentPath = currentPath,
            internalRoot = internalRoot,
            internalLabel = stringResource(R.string.loc_internal),
        )
    }

    LaunchedEffect(currentPath) {
        // 1フレーム待って新しい幅が確定してから末尾（現在地）へ滑らかに寄せる
        withFrameNanos { }
        scrollState.animateScrollTo(scrollState.maxValue, DangoMotion.fade())
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(colors.toolbar)
            .combinedClickable(
                interactionSource = null,
                indication = null,
                onClick = {},
                onLongClick = {
                    clipboard.setText(AnnotatedString(currentPath.displayPath()))
                    onPathCopied()
                },
            )
            .horizontalScroll(scrollState)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, (label, path) ->
            val isLast = index == crumbs.lastIndex
            PathCrumb(
                label = label,
                path = path,
                isRoot = index == 0,
                isLast = isLast,
                dropEnabled = onDropKeys != null && path.scheme == "file",
                onDropKeys = { keys -> onDropKeys?.invoke(path, keys) },
                onNavigate = { onNavigate(path) },
            )
            if (!isLast) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = colors.textSecondary.copy(alpha = 0.8f),
                    modifier = Modifier
                        .padding(horizontal = 1.dp)
                        .size(12.dp),
                )
            }
        }
    }
}

/**
 * パスバーの1階層（Finder: 16pt のアイコン＋名前。押すと角丸のハイライト）。
 * 内部ストレージのルートは端末、ゴミ箱はゴミ箱、ネットワークはサーバ、
 * タグは色の点、それ以外のフォルダは青いフォルダを付ける
 */
@Composable
private fun PathCrumb(
    label: String,
    path: FsPath,
    isRoot: Boolean,
    isLast: Boolean,
    dropEnabled: Boolean,
    onDropKeys: (Set<String>) -> Unit,
    onNavigate: () -> Unit,
) {
    val colors = DangoTheme.colors
    var dropHover by remember(path.key) { mutableStateOf(false) }
    val dropFill by animateColorAsState(
        targetValue = if (dropHover) colors.selectionFocused.copy(alpha = 0.15f) else Color.Transparent,
        animationSpec = DangoMotion.selectionIn(),
        label = "crumbDrop",
    )
    val bounce = remember { Animatable(1f) }
    LaunchedEffect(dropHover) {
        if (dropHover) bounce.animateTo(1.08f, DangoMotion.bounceUp())
        bounce.animateTo(1f, DangoMotion.bounceDown())
    }
    val shape = RoundedCornerShape(5.dp)
    Row(
        modifier = Modifier
            .graphicsLayer {
                scaleX = bounce.value
                scaleY = bounce.value
            }
            .clip(shape)
            .background(dropFill)
            .border(1.5.dp, if (dropHover) colors.selectionFocused else Color.Transparent, shape)
            .entryDropTarget(
                enabled = dropEnabled,
                onHover = { dropHover = it },
                onDropKeys = onDropKeys,
            )
            .swallowRightClick()
            .clickable(enabled = !isLast, onClick = onNavigate)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CrumbIcon(path = path, isRoot = isRoot)
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = colors.textPrimary,
            fontSize = 11.5.sp,
            fontWeight = if (isLast) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
    }
}

@Composable
private fun CrumbIcon(path: FsPath, isRoot: Boolean) {
    val colors = DangoTheme.colors
    val size = Modifier.size(14.dp)
    when {
        path.scheme == "tag" -> Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(TAG_COLOR_VALUES[path.segments.firstOrNull()] ?: colors.textSecondary),
        )
        path.scheme == "trash" ->
            Icon(Icons.Outlined.Delete, contentDescription = null, tint = colors.textSecondary, modifier = size)
        isRoot && NetPaths.isNetwork(path) ->
            Icon(Icons.Outlined.Dns, contentDescription = null, tint = colors.textSecondary, modifier = size)
        isRoot && path.scheme == "file" ->
            Icon(Icons.Outlined.Smartphone, contentDescription = null, tint = colors.textSecondary, modifier = size)
        else -> Image(imageVector = FinderFolder, contentDescription = null, modifier = size)
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
