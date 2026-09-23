package io.github.hatake716.dango.ui.browser.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.db.ConnectionEntity
import io.github.hatake716.dango.domain.model.FsPath
import io.github.hatake716.dango.ui.browser.SidebarItem
import io.github.hatake716.dango.ui.theme.DangoMotion
import io.github.hatake716.dango.ui.theme.DangoTheme

private val sidebarIcons: Map<String, ImageVector> = mapOf(
    "downloads" to Icons.Outlined.Download,
    "documents" to Icons.Outlined.Description,
    "pictures" to Icons.Outlined.Image,
    "movies" to Icons.Outlined.Movie,
    "music" to Icons.Outlined.MusicNote,
    "internal" to Icons.Outlined.Smartphone,
    "trash" to Icons.Outlined.Delete,
)

@Composable
fun SidebarContent(
    favorites: List<SidebarItem>,
    locations: List<SidebarItem>,
    currentPath: FsPath,
    onNavigate: (FsPath) -> Unit,
    modifier: Modifier = Modifier,
    /** ドラッグ&ドロップの受け口（SPEC §4.3）。null なら受け付けない */
    onDropKeys: ((SidebarItem, Set<String>) -> Unit)? = null,
    /** ネットワーク接続（SPEC §4.3）。M4 */
    connections: List<ConnectionEntity> = emptyList(),
    onOpenConnection: (ConnectionEntity) -> Unit = {},
    onEditConnection: (ConnectionEntity) -> Unit = {},
    onAddConnection: () -> Unit = {},
    /** タグ（SPEC §4.3）。M5 */
    tagColors: List<String> = emptyList(),
    onOpenTag: (String) -> Unit = {},
    /** クラウドリンク（タップで公式アプリへ。SPEC §15 #9） */
    onOpenCloudLink: ((CloudLink) -> Unit)? = null,
) {
    val colors = DangoTheme.colors
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(colors.sidebar)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        SidebarSectionLabel(stringResource(R.string.sidebar_favorites))
        favorites.forEach { item ->
            SidebarRow(item, selected = currentPath == item.path, onNavigate = onNavigate, onDropKeys = onDropKeys)
        }
        SidebarSectionLabel(stringResource(R.string.sidebar_locations))
        locations.forEach { item ->
            SidebarRow(item, selected = currentPath == item.path, onNavigate = onNavigate, onDropKeys = onDropKeys)
        }
        // ネットワーク（SPEC §4.3。長押し/右クリックで編集）
        SidebarSectionLabel(stringResource(R.string.sidebar_network))
        connections.forEach { conn ->
            NetworkRow(
                connection = conn,
                selected = currentPath.scheme == conn.protocol &&
                    currentPath.segments.firstOrNull() == conn.id.toString(),
                onOpen = { onOpenConnection(conn) },
                onEdit = { onEditConnection(conn) },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(SIDEBAR_ROW_HEIGHT)
                .clip(SidebarRowShape)
                .swallowRightClick()
                .clickable { onAddConnection() }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.net_add_connection),
                color = colors.textSecondary,
                fontSize = 13.sp,
            )
        }
        // クラウド: 公式アプリへのリンク（SPEC §15 #9。直接統合はスコープ外）
        if (onOpenCloudLink != null) {
                SidebarSectionLabel(stringResource(R.string.sidebar_cloud))
            CLOUD_LINKS.forEach { link ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SIDEBAR_ROW_HEIGHT)
                        .clip(SidebarRowShape)
                        .swallowRightClick()
                        .clickable { onOpenCloudLink(link) }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = link.icon,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(link.labelRes),
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        // タグ（SPEC §4.3: 色付きドット。タップでタグ検索）
        if (tagColors.isNotEmpty()) {
                SidebarSectionLabel(stringResource(R.string.sidebar_tags))
            tagColors.forEach { tag ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SIDEBAR_ROW_HEIGHT)
                        .clip(SidebarRowShape)
                        .swallowRightClick()
                        .clickable { onOpenTag(tag) }
                        .padding(horizontal = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(TAG_COLOR_VALUES[tag] ?: colors.textSecondary)
                            .border(0.5.dp, colors.textPrimary.copy(alpha = 0.15f), CircleShape),
                    )
                    Spacer(Modifier.width(11.dp))
                    Text(
                        text = stringResource(tagLabelRes(tag)),
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NetworkRow(
    connection: ConnectionEntity,
    selected: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
) {
    val colors = DangoTheme.colors
    val background by animateColorAsState(
        targetValue = if (selected) colors.selectionUnfocused else Color.Transparent,
        animationSpec = DangoMotion.selectionIn(),
        label = "netRowBg",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SIDEBAR_ROW_HEIGHT)
            .clip(SidebarRowShape)
            .background(background)
            .onRightClick { onEdit() }
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onEdit,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Dns,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = connection.name,
            color = colors.textPrimary,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

/** 行の高さ（Finder の中サイズ 28pt をタッチ向けに少し広げた値。全セクション共通） */
private val SIDEBAR_ROW_HEIGHT = 32.dp
private val SidebarRowShape = RoundedCornerShape(6.dp)

@Composable
private fun SidebarSectionLabel(text: String) {
    // Finder の見出し: 11pt 太字の補助色。セクション間の余白は見出しの上に取る
    Text(
        text = text,
        color = DangoTheme.colors.textSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 3.dp),
    )
}

@Composable
private fun SidebarRow(
    item: SidebarItem,
    selected: Boolean,
    onNavigate: (FsPath) -> Unit,
    onDropKeys: ((SidebarItem, Set<String>) -> Unit)?,
) {
    val colors = DangoTheme.colors
    var dropHover by remember { mutableStateOf(false) }
    val background by animateColorAsState(
        targetValue = when {
            dropHover -> colors.selectionFocused.copy(alpha = 0.15f)
            selected -> colors.selectionUnfocused
            else -> Color.Transparent
        },
        animationSpec = DangoMotion.selectionIn(),
        label = "sidebarRowBg",
    )
    val border by animateColorAsState(
        targetValue = if (dropHover) colors.selectionFocused else Color.Transparent,
        animationSpec = DangoMotion.selectionIn(),
        label = "sidebarRowBorder",
    )
    val isTrash = item.id == "trash"
    // ゴミ箱行: 削除した項目が吸い込まれた瞬間にアイコンを弾ませる
    val iconScale = remember { Animatable(1f) }
    val landed = LocalItemBounds.current?.trashLanded
    if (isTrash && landed != null) {
        val tick = landed.intValue
        LaunchedEffect(tick) {
            if (tick > 0) {
                iconScale.animateTo(1.22f, DangoMotion.bounceUp())
                iconScale.animateTo(1f, DangoMotion.bounceDown())
            }
        }
    }
    // ドロップ先ホバーでも軽く弾ませる（SPEC §5 ドラッグ）
    LaunchedEffect(dropHover) {
        if (dropHover) iconScale.animateTo(1.12f, DangoMotion.bounceUp())
        iconScale.animateTo(1f, DangoMotion.bounceDown())
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SIDEBAR_ROW_HEIGHT)
            .clip(SidebarRowShape)
            .background(background)
            .border(2.dp, border, SidebarRowShape)
            .entryDropTarget(
                enabled = onDropKeys != null,
                onHover = { dropHover = it },
                onDropKeys = { keys -> onDropKeys?.invoke(item, keys) },
            )
            .swallowRightClick()
            .clickable { onNavigate(item.path) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = sidebarIcons[item.id] ?: Icons.Outlined.Description,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier
                .size(17.dp)
                .then(if (isTrash) Modifier.registerAnimationTarget(TrashTargets.SIDEBAR) else Modifier)
                .graphicsLayer {
                    scaleX = iconScale.value
                    scaleY = iconScale.value
                },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(item.labelRes),
            color = colors.textPrimary,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}
