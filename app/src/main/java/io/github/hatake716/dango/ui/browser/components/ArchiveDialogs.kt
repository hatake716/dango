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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.archive.CompressFormat
import io.github.hatake716.dango.ui.theme.DangoTheme

/** 圧縮オプション（SPEC §6.4: zip 既定 / tar.gz / 7z、パスワード・レベル・元を削除） */
@Composable
fun CompressDialog(
    itemCount: Int,
    onApply: (format: CompressFormat, level: Int, password: String?, deleteSource: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DangoTheme.colors
    var format by remember { mutableStateOf(CompressFormat.ZIP) }
    var level by remember { mutableIntStateOf(2) }
    var password by remember { mutableStateOf("") }
    var deleteSource by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compress_title, itemCount)) },
        text = {
            Column {
                Row {
                    CompressFormat.entries.forEach { f ->
                        FilterChip(
                            selected = format == f,
                            onClick = { format = f },
                            label = { Text(f.extension, fontSize = 12.sp) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                if (format == CompressFormat.ZIP) {
                    // 圧縮レベルとパスワードは zip のみ有効（SPEC §15 #8: 7z 暗号化は未対応）
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.compress_level), fontSize = 12.sp, color = colors.textSecondary)
                    Row {
                        listOf(
                            1 to R.string.compress_level_fast,
                            2 to R.string.compress_level_normal,
                            3 to R.string.compress_level_max,
                        ).forEach { (value, labelRes) ->
                            FilterChip(
                                selected = level == value,
                                onClick = { level = value },
                                label = { Text(stringResource(labelRes), fontSize = 12.sp) },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.compress_password_optional)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteSource, onCheckedChange = { deleteSource = it })
                    Text(stringResource(R.string.compress_delete_source), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(format, level, password.takeIf { it.isNotEmpty() }, deleteSource) },
            ) { Text(stringResource(R.string.act_compress)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** 「オプションを指定して展開」: 文字コードと同名フォルダ有無（SPEC §6.4） */
@Composable
fun ExtractOptionsDialog(
    entryName: String,
    onApply: (encoding: String?, wrapInFolder: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DangoTheme.colors
    var encoding by remember { mutableStateOf<String?>(null) }
    var wrap by remember { mutableStateOf(true) }
    val encodings = listOf(
        null to R.string.encoding_auto,
        "UTF-8" to R.string.encoding_utf8,
        "Shift_JIS" to R.string.encoding_sjis,
        "EUC-JP" to R.string.encoding_eucjp,
        "CP437" to R.string.encoding_cp437,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entryName, maxLines = 1) },
        text = {
            Column {
                Text(
                    stringResource(R.string.extract_encoding_label),
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                )
                encodings.chunked(3).forEach { rowItems ->
                    Row {
                        rowItems.forEach { (value, labelRes) ->
                            FilterChip(
                                selected = encoding == value,
                                onClick = { encoding = value },
                                label = { Text(stringResource(labelRes), fontSize = 11.sp) },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = wrap, onCheckedChange = { wrap = it })
                    Text(stringResource(R.string.extract_wrap_folder), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(encoding, wrap) }) {
                Text(stringResource(R.string.ctx_extract_short))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** アーカイブのパスワード入力（SPEC §6.4: zip AES/ZipCrypto・7z・rar） */
@Composable
fun ArchivePasswordDialog(
    archiveName: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.archive_password_title)) },
        text = {
            Column {
                Text(stringResource(R.string.archive_password_body, archiveName))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.ql_pdf_password_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(value) }) {
                Text(stringResource(R.string.ql_pdf_unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        },
    )
}
