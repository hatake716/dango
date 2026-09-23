package io.github.hatake716.dango.ui.quicklook

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.ui.browser.components.EntryKindIcon
import io.github.hatake716.dango.ui.theme.DangoMotion

/** 上部バーの高さ（ページ領域はこの下から始まる） */
internal val QL_BAR_HEIGHT = 44.dp

/** Finder の Quick Look の暗いウインドウ枠 */
private val BarColor = Color(0xFF1E1E1E).copy(alpha = 0.85f)
private val Hairline = Color.White.copy(alpha = 0.08f)

/** この幅より狭いと「別のアプリで開く」をアイコンだけにする */
private val PILL_MIN_WIDTH = 380.dp

/**
 * Quick Look の上部バー（Finder 風）: 左に閉じる、中央にファイル名、
 * 右に「別のアプリで開く」・共有・情報
 */
@Composable
internal fun QuickLookTopBar(
    current: FsEntry?,
    pageIndex: Int,
    pageCount: Int,
    onClose: () -> Unit,
    onOpenWith: () -> Unit,
    onShare: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(BarColor)
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(Hairline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(QL_BAR_HEIGHT)
            .padding(horizontal = 6.dp),
    ) {
        val compact = maxWidth < PILL_MIN_WIDTH
        CenteredTitleLayout(
            leading = { CloseButton(onClose) },
            title = { Title(current, pageIndex, pageCount) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (compact) {
                        BarIconButton(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.ql_open_with), onOpenWith)
                    } else {
                        OpenWithPill(onOpenWith)
                        Spacer(Modifier.width(4.dp))
                    }
                    BarIconButton(Icons.Outlined.IosShare, stringResource(R.string.ql_share), onShare)
                    BarIconButton(Icons.Outlined.Info, stringResource(R.string.ql_info), onInfo)
                }
            },
        )
    }
}

/**
 * 題名はバーの中央に置き、左右のボタンと重なるときだけ空いている側へずらす
 */
@Composable
private fun CenteredTitleLayout(
    leading: @Composable () -> Unit,
    title: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(leading, title, trailing),
        modifier = Modifier.fillMaxSize(),
    ) { (leadingM, titleM, trailingM), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val lead = leadingM.first().measure(loose)
        val trail = trailingM.first().measure(loose)
        val gap = 8.dp.roundToPx()
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val maxTitle = (w - lead.width - trail.width - gap * 2).coerceAtLeast(0)
        val titleP = titleM.first().measure(loose.copy(maxWidth = maxTitle))
        layout(w, h) {
            lead.placeRelative(0, (h - lead.height) / 2)
            trail.placeRelative(w - trail.width, (h - trail.height) / 2)
            val min = lead.width + gap
            val max = (w - trail.width - gap - titleP.width).coerceAtLeast(min)
            titleP.placeRelative(((w - titleP.width) / 2).coerceIn(min, max), (h - titleP.height) / 2)
        }
    }
}

@Composable
private fun Title(current: FsEntry?, pageIndex: Int, pageCount: Int) {
    // ページ送りでファイル名をクロスフェード
    AnimatedContent(
        targetState = current?.let { it to pageIndex },
        contentKey = { it?.first?.path?.key },
        transitionSpec = {
            fadeIn(DangoMotion.fade()) togetherWith fadeOut(DangoMotion.fade()) using
                SizeTransform(clip = false) { _, _ -> DangoMotion.fade() }
        },
        contentAlignment = Alignment.Center,
        label = "qlTitle",
    ) { target ->
        val (entry, index) = target ?: return@AnimatedContent
        Row(verticalAlignment = Alignment.CenterVertically) {
            EntryKindIcon(kind = entry.kind, name = entry.name, size = 16.dp)
            Spacer(Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = entry.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                if (pageCount > 1) {
                    Text(
                        text = stringResource(R.string.ql_page_of, index + 1, pageCount),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 閉じる: 小さな丸い✕（触れる範囲は見た目より広く取る） */
@Composable
private fun CloseButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .indication(interaction, LocalIndication.current),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ql_close),
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 「別のアプリで開く」の角丸ボタン（Finder の「"プレビュー"で開く」に相当） */
@Composable
private fun OpenWithPill(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .height(40.dp)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color.White.copy(alpha = 0.14f))
                .indication(interaction, LocalIndication.current)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.ql_open_with),
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BarIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}
