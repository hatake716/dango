package io.github.hatake716.dango.ui.browser.components

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.db.ConnectionEntity
import io.github.hatake716.dango.domain.model.FsPath
import io.github.hatake716.dango.ui.browser.SidebarItem
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
        Spacer(Modifier.height(14.dp))
        SidebarSectionLabel(stringResource(R.string.sidebar_locations))
        locations.forEach { item ->
            SidebarRow(item, selected = currentPath == item.path, onNavigate = onNavigate, onDropKeys = onDropKeys)
        }
        // ネットワーク（SPEC §4.3。長押し/右クリックで編集）
        Spacer(Modifier.height(14.dp))
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
                .height(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .swallowRightClick()
                .clickable { onAddConnection() }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.net_add_connection),
                color = colors.textSecondary,
                fontSize = 12.sp,
            )
        }
        // タグ（SPEC §4.3: 色付きドット。タップでタグ検索）
        if (tagColors.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SidebarSectionLabel(stringResource(R.string.sidebar_tags))
            tagColors.forEach { tag ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .swallowRightClick()
                        .clickable { onOpenTag(tag) }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(TAG_COLOR_VALUES[tag] ?: colors.textSecondary),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = stringResource(tagLabelRes(tag)),
                        color = colors.textPrimary,
                        fontSize = 12.sp,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.selectionUnfocused else colors.sidebar)
            .onRightClick { onEdit() }
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onEdit,
            )
            .padding(horizontal = 8.dp),
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

@Composable
private fun SidebarSectionLabel(text: String) {
    Text(
        text = text,
        color = DangoTheme.colors.textSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.selectionUnfocused else colors.sidebar)
            .then(
                if (dropHover) {
                    Modifier.border(2.dp, colors.selectionFocused, RoundedCornerShape(6.dp))
                } else {
                    Modifier
                },
            )
            .entryDropTarget(
                enabled = onDropKeys != null,
                onHover = { dropHover = it },
                onDropKeys = { keys -> onDropKeys?.invoke(item, keys) },
            )
            .swallowRightClick()
            .clickable { onNavigate(item.path) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = sidebarIcons[item.id] ?: Icons.Outlined.Description,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(17.dp),
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
