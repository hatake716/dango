package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.fs.NameUtils
import io.github.hatake716.dango.data.transfer.ConflictRequest
import io.github.hatake716.dango.domain.model.ConflictChoice
import io.github.hatake716.dango.domain.model.ConflictResolution
import io.github.hatake716.dango.ui.theme.DangoTheme

/** 同名衝突ダイアログ（SPEC §6.3: 両方残す / 置き換え / スキップ＋以降すべてに適用） */
@Composable
fun ConflictDialog(request: ConflictRequest) {
    // 次の衝突が続けて来たときは新しいダイアログとして出し直す
    key(request) {
        var applyToAll by remember { mutableStateOf(false) }
        fun answer(resolution: ConflictResolution) {
            request.response.complete(ConflictChoice(resolution, applyToAll))
        }
        FinderAlertDialog(
            onDismissRequest = { answer(ConflictResolution.CANCEL_ALL) },
            icon = { FinderAppIcon() },
            title = stringResource(R.string.conflict_title, request.name),
            message = stringResource(R.string.conflict_body),
            buttons = listOf(
                FinderAlertButton(
                    text = stringResource(R.string.conflict_keep_both),
                    style = FinderButtonStyle.Default,
                ) { answer(ConflictResolution.KEEP_BOTH) },
                FinderAlertButton(
                    text = stringResource(R.string.conflict_replace),
                    style = FinderButtonStyle.Destructive,
                ) { answer(ConflictResolution.REPLACE) },
                FinderAlertButton(stringResource(R.string.conflict_skip)) {
                    answer(ConflictResolution.SKIP)
                },
                FinderAlertButton(stringResource(R.string.conflict_cancel_all)) {
                    answer(ConflictResolution.CANCEL_ALL)
                },
            ),
        ) {
            FinderCheckboxRow(
                checked = applyToAll,
                onCheckedChange = { applyToAll = it },
                text = stringResource(R.string.conflict_apply_all),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 完全削除の確認（SPEC §6.3: 完全削除は確認ダイアログ必須） */
@Composable
fun DeleteConfirmDialog(
    count: Int,
    emptyAll: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    FinderAlertDialog(
        onDismissRequest = onDismiss,
        icon = { FinderAppIcon() },
        title = stringResource(R.string.delete_confirm_title),
        message = if (emptyAll) {
            stringResource(R.string.empty_trash_confirm_body)
        } else {
            stringResource(R.string.delete_confirm_body, count)
        },
        buttons = listOf(
            FinderAlertButton(
                text = stringResource(R.string.delete_confirm_ok),
                style = FinderButtonStyle.Destructive,
                onClick = onConfirm,
            ),
            FinderAlertButton(text = stringResource(R.string.cancel), onClick = onDismiss),
        ),
    )
}

private enum class BatchMode { SEQUENCE, REPLACE, AFFIX }

/** 一括リネーム（SPEC §6.3: 連番・検索置換・接頭辞/接尾辞） */
@Composable
fun BatchRenameDialog(
    firstName: String,
    onApply: (transform: (index: Int, name: String, isDir: Boolean) -> String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DangoTheme.colors
    var mode by remember { mutableStateOf(BatchMode.SEQUENCE) }
    var baseName by remember { mutableStateOf("") }
    var startNumber by remember { mutableStateOf("1") }
    var find by remember { mutableStateOf("") }
    var replaceWith by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("") }
    var suffix by remember { mutableStateOf("") }

    fun transform(index: Int, name: String, isDir: Boolean): String {
        val (base, ext) = NameUtils.splitExtension(name, isDir)
        return when (mode) {
            BatchMode.SEQUENCE -> {
                val start = startNumber.toIntOrNull() ?: 1
                val stem = baseName.ifBlank { base }
                "$stem ${start + index}$ext"
            }
            BatchMode.REPLACE ->
                if (find.isEmpty()) name else base.replace(find, replaceWith) + ext
            BatchMode.AFFIX -> "$prefix$base$suffix$ext"
        }
    }

    FinderAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.batch_rename_title),
        width = 320.dp,
        buttons = listOf(
            FinderAlertButton(
                text = stringResource(R.string.batch_apply),
                style = FinderButtonStyle.Default,
            ) { onApply(::transform) },
            FinderAlertButton(text = stringResource(R.string.cancel), onClick = onDismiss),
        ),
    ) {
        FinderSegmented(
            options = listOf(
                stringResource(R.string.batch_mode_seq),
                stringResource(R.string.batch_mode_replace),
                stringResource(R.string.batch_mode_affix),
            ),
            selectedIndex = mode.ordinal,
            onSelect = { mode = BatchMode.entries[it] },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        when (mode) {
            BatchMode.SEQUENCE -> {
                FinderTextField(
                    value = baseName,
                    onValueChange = { baseName = it },
                    label = stringResource(R.string.batch_base_name),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                FinderTextField(
                    value = startNumber,
                    onValueChange = { startNumber = it },
                    label = stringResource(R.string.batch_start_number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            BatchMode.REPLACE -> {
                FinderTextField(
                    value = find,
                    onValueChange = { find = it },
                    label = stringResource(R.string.batch_find),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                FinderTextField(
                    value = replaceWith,
                    onValueChange = { replaceWith = it },
                    label = stringResource(R.string.batch_replace_with),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            BatchMode.AFFIX -> {
                FinderTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = stringResource(R.string.batch_prefix),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                FinderTextField(
                    value = suffix,
                    onValueChange = { suffix = it },
                    label = stringResource(R.string.batch_suffix),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(
                R.string.batch_preview,
                transform(0, firstName, isDir = false),
            ),
            color = colors.textSecondary,
            fontSize = 12.sp,
        )
    }
}
