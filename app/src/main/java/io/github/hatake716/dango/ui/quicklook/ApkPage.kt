package io.github.hatake716.dango.ui.quicklook

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.apk.ApkAction
import io.github.hatake716.dango.data.apk.ApkBlocker
import io.github.hatake716.dango.data.apk.ApkInfo
import io.github.hatake716.dango.data.apk.ApkInstallPhase
import io.github.hatake716.dango.data.apk.ApkInstallState
import io.github.hatake716.dango.data.apk.ApkPermission
import io.github.hatake716.dango.domain.model.EntryKind
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.ui.browser.components.EntryKindIcon
import io.github.hatake716.dango.ui.theme.DangoMotion
import io.github.hatake716.dango.ui.theme.DangoTheme
import io.github.hatake716.dango.ui.util.androidVersionName
import io.github.hatake716.dango.ui.util.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private val TextMain = Color.White.copy(alpha = 0.92f)
private val TextDim = Color.White.copy(alpha = 0.55f)
private val GroupFill = Color.White.copy(alpha = 0.07f)
private val GroupStroke = Color.White.copy(alpha = 0.08f)
private val Hairline = Color.White.copy(alpha = 0.09f)
private val BlockerRed = Color(0xFFFF5257)
private val WarnOrange = Color(0xFFFFA234)
private val GroupShape = RoundedCornerShape(10.dp)
private val MaxContentWidth = 560.dp

/** 操作欄の表示モード（切替時だけクロスフェードさせる） */
private enum class ActionMode { BUTTONS, COPYING, WAITING }

/**
 * APK の Quick Look（SPEC §6.5: アイコン、パッケージ名、バージョン、権限一覧、インストールボタン）。
 * macOS の情報パネル風に、アイコンと名前・操作ボタン・項目表・権限一覧を縦に並べる。
 * インストールの進行状態はアプリ単位の [ApkInstallState] から読む（ページを離れて戻っても進捗が残る）
 */
@Composable
fun ApkPage(
    entry: FsEntry,
    loadInfo: suspend (FsEntry) -> ApkInfo,
    installState: ApkInstallState,
    onInstall: (FsEntry, String?) -> Unit,
    onCancel: () -> Unit,
    onOpenApp: (String) -> Unit,
) {
    // 結果が出るたびに再解析する（インストール済み版・開くボタンの更新）。前回の表示は残したまま差し替える
    val inspected by produceState<Pair<Int, Result<ApkInfo>>?>(null, entry.path.key, installState.generation) {
        val generation = installState.generation
        value = generation to try {
            Result.success(loadInfo(entry))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    when (val r = inspected) {
        null -> {
            // 解析済み（キャッシュ）ならすぐ出るので、スピナーは遅れて出してちらつかせない
            var showSpinner by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(DangoMotion.SPINNER_DELAY_MS.toLong())
                showSpinner = true
            }
            if (showSpinner) QlLoading()
        }
        else -> r.second.fold(
            onSuccess = { info ->
                val stale = r.first != installState.generation
                ApkPanel(entry, info, installState, stale, onInstall, onCancel, onOpenApp)
            },
            onFailure = { ApkParseError(entry) },
        )
    }
}

@Composable
private fun ApkPanel(
    entry: FsEntry,
    info: ApkInfo,
    installState: ApkInstallState,
    stale: Boolean,
    onInstall: (FsEntry, String?) -> Unit,
    onCancel: () -> Unit,
    onOpenApp: (String) -> Unit,
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val itemModifier = Modifier
        .widthIn(max = MaxContentWidth)
        .fillMaxWidth()
        .padding(horizontal = 20.dp)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(top = 28.dp, bottom = 32.dp + bottomInset),
    ) {
        item(key = "header") {
            ApkHeader(entry, info, itemModifier)
        }
        item(key = "actions") {
            ApkActions(
                entry = entry,
                info = info,
                installState = installState,
                stale = stale,
                onInstall = onInstall,
                onCancel = onCancel,
                onOpenApp = onOpenApp,
                modifier = itemModifier.padding(top = 18.dp),
            )
        }
        item(key = "details") {
            ApkDetails(info, itemModifier.padding(top = 26.dp))
        }
        item(key = "permissionsHeader") {
            PermissionsHeader(info.permissions, itemModifier.padding(top = 26.dp, bottom = 8.dp))
        }
        if (info.permissions.isEmpty()) {
            item(key = "noPermissions") {
                Box(itemModifier) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(GroupShape)
                            .background(GroupFill)
                            .border(0.5.dp, GroupStroke, GroupShape)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                    ) {
                        Text(stringResource(R.string.ql_apk_no_permissions), color = TextDim, fontSize = 13.sp)
                    }
                }
            }
        } else {
            val last = info.permissions.lastIndex
            itemsIndexed(info.permissions, key = { _, p -> "perm:" + p.name }) { i, p ->
                PermissionRow(p, first = i == 0, last = i == last, modifier = itemModifier)
            }
        }
    }
}

@Composable
private fun ApkHeader(entry: FsEntry, info: ApkInfo, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val icon = info.icon
        if (icon != null) {
            val bitmap = remember(icon) { icon.asImageBitmap() }
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(96.dp))
        } else {
            EntryKindIcon(kind = EntryKind.APK, name = entry.name, size = 96.dp)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = info.label,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = stringResource(R.string.ql_apk_version_value, info.versionName ?: "–", info.versionCode),
            color = TextDim,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ApkActions(
    entry: FsEntry,
    info: ApkInfo,
    installState: ApkInstallState,
    stale: Boolean,
    onInstall: (FsEntry, String?) -> Unit,
    onCancel: () -> Unit,
    onOpenApp: (String) -> Unit,
    modifier: Modifier,
) {
    val mine = installState.targetKey == entry.path.key
    val mode = when {
        mine && installState.phase == ApkInstallPhase.COPYING -> ActionMode.COPYING
        mine && installState.phase != ApkInstallPhase.IDLE -> ActionMode.WAITING
        else -> ActionMode.BUTTONS
    }
    AnimatedContent(
        targetState = mode,
        transitionSpec = {
            fadeIn(DangoMotion.fade()) togetherWith fadeOut(DangoMotion.fade()) using SizeTransform(clip = false)
        },
        contentAlignment = Alignment.TopCenter,
        label = "apkActions",
        modifier = modifier,
    ) { m ->
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            when (m) {
                ActionMode.BUTTONS -> ActionButtons(entry, info, installState, stale, onInstall, onOpenApp)
                ActionMode.COPYING, ActionMode.WAITING -> {
                    val progress by animateFloatAsState(installState.progress, DangoMotion.bar(), label = "apkProgress")
                    val accent = DangoTheme.colors.selectionFocused
                    val barModifier = Modifier
                        .padding(top = 8.dp)
                        .width(260.dp)
                        .height(5.dp)
                    if (m == ActionMode.COPYING) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = barModifier,
                            color = accent,
                            trackColor = Color.White.copy(alpha = 0.16f),
                            strokeCap = StrokeCap.Round,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = barModifier,
                            color = accent,
                            trackColor = Color.White.copy(alpha = 0.16f),
                            strokeCap = StrokeCap.Round,
                            gapSize = 0.dp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when {
                            m == ActionMode.COPYING ->
                                stringResource(R.string.ql_apk_copying, (installState.progress * 100).toInt())
                            installState.phase == ApkInstallPhase.AWAITING_USER ->
                                stringResource(R.string.ql_apk_waiting)
                            else -> stringResource(R.string.ql_apk_installing)
                        },
                        color = TextDim,
                        fontSize = 12.sp,
                    )
                    // 承認後の実インストール中は取り消せない（中断すると結果が分からなくなる）
                    if (installState.phase != ApkInstallPhase.INSTALLING) {
                        Spacer(Modifier.height(12.dp))
                        PushButton(text = stringResource(R.string.cancel), primary = false, onClick = onCancel)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButtons(
    entry: FsEntry,
    info: ApkInfo,
    installState: ApkInstallState,
    stale: Boolean,
    onInstall: (FsEntry, String?) -> Unit,
    onOpenApp: (String) -> Unit,
) {
    val busy = installState.phase != ApkInstallPhase.IDLE
    val canInstall = info.blocker == ApkBlocker.NONE && !busy
    val installLabel = stringResource(
        when (info.action) {
            ApkAction.UPDATE -> R.string.ql_apk_update
            ApkAction.REINSTALL -> R.string.ql_apk_reinstall
            ApkAction.INSTALL, ApkAction.DOWNGRADE -> R.string.ql_apk_install
        },
    )
    // 再解析中は古い判定のまま押させない（完了直後の二重インストール防止）。見た目は変えずちらつかせない
    val install = { if (!stale) onInstall(entry, info.packageName) }
    // 同じ版が入っている・入れられないときは「開く」を既定ボタンにする（macOS の既定ボタンは右端）
    val openIsDefault = info.launchable && (info.action == ApkAction.REINSTALL || info.blocker != ApkBlocker.NONE)
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (openIsDefault) {
            PushButton(installLabel, primary = false, enabled = canInstall, onClick = install)
            PushButton(stringResource(R.string.ql_apk_open), primary = true) { onOpenApp(info.packageName) }
        } else {
            if (info.launchable) {
                PushButton(stringResource(R.string.ql_apk_open), primary = false) { onOpenApp(info.packageName) }
            }
            PushButton(installLabel, primary = true, enabled = canInstall, onClick = install)
        }
    }
    val note: Pair<String, Color>? = when {
        info.blocker != ApkBlocker.NONE -> blockerText(info) to BlockerRed
        busy -> stringResource(R.string.ql_apk_busy) to TextDim
        info.oldTargetWarning -> stringResource(R.string.ql_apk_old_target) to WarnOrange
        else -> null
    }
    note?.let { (text, color) ->
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            // 文節単位で行を均等に割り、末尾1文字だけの折り返しを避ける
            style = LocalTextStyle.current.copy(lineBreak = LineBreak.Heading),
            modifier = Modifier
                .padding(top = 10.dp)
                .widthIn(max = 400.dp),
        )
    }
}

@Composable
private fun blockerText(info: ApkInfo): String = when (info.blocker) {
    ApkBlocker.MIN_SDK -> stringResource(R.string.ql_apk_min_sdk_block, androidVersionName(info.minSdk))
    ApkBlocker.UNSIGNED -> stringResource(R.string.ql_apk_unsigned)
    ApkBlocker.TEST_ONLY -> stringResource(R.string.ql_apk_test_only)
    ApkBlocker.DOWNGRADE -> stringResource(R.string.ql_apk_downgrade)
    ApkBlocker.SIGNATURE_MISMATCH -> stringResource(R.string.ql_apk_signature_mismatch)
    ApkBlocker.NONE -> ""
}

/**
 * macOS のプッシュボタン（既定ボタンはアクセント色、それ以外は半透明のグレー）。
 * 押下表現は全体の FinderPressIndication に任せる
 */
@Composable
private fun PushButton(
    text: String,
    primary: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(7.dp)
    val fill = when {
        !enabled -> Color.White.copy(alpha = 0.10f)
        primary -> DangoTheme.colors.selectionFocused
        else -> Color.White.copy(alpha = 0.18f)
    }
    Box(
        modifier = Modifier
            .height(36.dp)
            .widthIn(min = 116.dp)
            .clip(shape)
            .background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun ApkDetails(info: ApkInfo, modifier: Modifier) {
    val rows = listOf(
        stringResource(R.string.ql_apk_package) to info.packageName,
        stringResource(R.string.info_size) to formatSize(info.sizeBytes),
        stringResource(R.string.ql_apk_min_sdk_label) to
            stringResource(R.string.ql_apk_android_version, androidVersionName(info.minSdk), info.minSdk),
        stringResource(R.string.ql_apk_target_sdk_label) to
            stringResource(R.string.ql_apk_android_version, androidVersionName(info.targetSdk), info.targetSdk),
        stringResource(R.string.ql_apk_installed_label) to (
            info.installed?.let {
                stringResource(R.string.ql_apk_installed_value, it.versionName ?: "–", it.versionCode)
            } ?: stringResource(R.string.ql_apk_not_installed)
            ),
    )
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GroupShape)
                .background(GroupFill)
                .border(0.5.dp, GroupStroke, GroupShape),
        ) {
            rows.forEachIndexed { i, (label, value) ->
                if (i > 0) HairlineDivider(start = 14.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(text = label, color = TextDim, fontSize = 13.sp, maxLines = 1)
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = value,
                        color = TextMain,
                        fontSize = 13.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionsHeader(permissions: List<ApkPermission>, modifier: Modifier) {
    Row(modifier = modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.ql_apk_permissions, permissions.size),
            color = TextMain,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (permissions.any { it.dangerous }) {
            DangerDot()
            Spacer(Modifier.width(5.dp))
            Text(stringResource(R.string.ql_apk_dangerous_legend), color = TextDim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PermissionRow(p: ApkPermission, first: Boolean, last: Boolean, modifier: Modifier) {
    val shape: Shape = when {
        first && last -> GroupShape
        first -> RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
        last -> RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)
        else -> RoundedCornerShape(0.dp)
    }
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(GroupFill),
        ) {
            if (!first) HairlineDivider(start = 32.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(8.dp), contentAlignment = Alignment.Center) {
                    if (p.dangerous) DangerDot()
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = p.label?.replaceFirstChar { it.titlecase() } ?: p.name,
                        color = TextMain,
                        fontSize = 13.sp,
                    )
                    if (p.label != null) {
                        Text(
                            text = p.name,
                            color = TextDim,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DangerDot() {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(WarnOrange),
    )
}

@Composable
private fun HairlineDivider(start: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .padding(start = start)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(Hairline),
    )
}

@Composable
private fun ApkParseError(entry: FsEntry) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EntryKindIcon(kind = EntryKind.APK, name = entry.name, size = 96.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.ql_apk_parse_error),
            color = TextMain,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            style = LocalTextStyle.current.copy(lineBreak = LineBreak.Heading),
            modifier = Modifier.widthIn(max = 360.dp),
        )
    }
}
