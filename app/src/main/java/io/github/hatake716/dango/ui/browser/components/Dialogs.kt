package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    var applyToAll by remember { mutableStateOf(false) }
    fun answer(resolution: ConflictResolution) {
        request.response.complete(ConflictChoice(resolution, applyToAll))
    }
    AlertDialog(
        onDismissRequest = { answer(ConflictResolution.CANCEL_ALL) },
        title = { Text(stringResource(R.string.conflict_title, request.name)) },
        text = {
            Column {
                Text(stringResource(R.string.conflict_body))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = applyToAll, onCheckedChange = { applyToAll = it })
                    Text(stringResource(R.string.conflict_apply_all), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { answer(ConflictResolution.KEEP_BOTH) }) {
                Text(stringResource(R.string.conflict_keep_both))
            }
            TextButton(onClick = { answer(ConflictResolution.REPLACE) }) {
                Text(stringResource(R.string.conflict_replace))
            }
            TextButton(onClick = { answer(ConflictResolution.SKIP) }) {
                Text(stringResource(R.string.conflict_skip))
            }
        },
        dismissButton = {
            TextButton(onClick = { answer(ConflictResolution.CANCEL_ALL) }) {
                Text(stringResource(R.string.conflict_cancel_all))
            }
        },
    )
}

/** 完全削除の確認（SPEC §6.3: 完全削除は確認ダイアログ必須） */
@Composable
fun DeleteConfirmDialog(
    count: Int,
    emptyAll: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_confirm_title)) },
        text = {
            Text(
                if (emptyAll) {
                    stringResource(R.string.empty_trash_confirm_body)
                } else {
                    stringResource(R.string.delete_confirm_body, count)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete_confirm_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch_rename_title)) },
        text = {
            Column {
                Row {
                    FilterChip(
                        selected = mode == BatchMode.SEQUENCE,
                        onClick = { mode = BatchMode.SEQUENCE },
                        label = { Text(stringResource(R.string.batch_mode_seq), fontSize = 12.sp) },
                    )
                    Spacer(Modifier.padding(2.dp))
                    FilterChip(
                        selected = mode == BatchMode.REPLACE,
                        onClick = { mode = BatchMode.REPLACE },
                        label = { Text(stringResource(R.string.batch_mode_replace), fontSize = 12.sp) },
                    )
                    Spacer(Modifier.padding(2.dp))
                    FilterChip(
                        selected = mode == BatchMode.AFFIX,
                        onClick = { mode = BatchMode.AFFIX },
                        label = { Text(stringResource(R.string.batch_mode_affix), fontSize = 12.sp) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                when (mode) {
                    BatchMode.SEQUENCE -> {
                        OutlinedTextField(
                            value = baseName,
                            onValueChange = { baseName = it },
                            label = { Text(stringResource(R.string.batch_base_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = startNumber,
                            onValueChange = { startNumber = it },
                            label = { Text(stringResource(R.string.batch_start_number)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    BatchMode.REPLACE -> {
                        OutlinedTextField(
                            value = find,
                            onValueChange = { find = it },
                            label = { Text(stringResource(R.string.batch_find)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = replaceWith,
                            onValueChange = { replaceWith = it },
                            label = { Text(stringResource(R.string.batch_replace_with)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    BatchMode.AFFIX -> {
                        OutlinedTextField(
                            value = prefix,
                            onValueChange = { prefix = it },
                            label = { Text(stringResource(R.string.batch_prefix)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = suffix,
                            onValueChange = { suffix = it },
                            label = { Text(stringResource(R.string.batch_suffix)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.batch_preview,
                        transform(0, firstName, isDir = false),
                    ),
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(::transform) }) {
                Text(stringResource(R.string.batch_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
