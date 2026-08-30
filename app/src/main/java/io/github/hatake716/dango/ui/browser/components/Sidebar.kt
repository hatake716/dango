package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
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
            SidebarRow(item, selected = currentPath == item.path, onNavigate = onNavigate)
        }
        Spacer(Modifier.height(14.dp))
        SidebarSectionLabel(stringResource(R.string.sidebar_locations))
        locations.forEach { item ->
            SidebarRow(item, selected = currentPath == item.path, onNavigate = onNavigate)
        }
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
) {
    val colors = DangoTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.selectionUnfocused else colors.sidebar)
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
