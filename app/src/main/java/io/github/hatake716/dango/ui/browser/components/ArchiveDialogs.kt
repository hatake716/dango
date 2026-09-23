package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.archive.CompressFormat
import io.github.hatake716.dango.domain.model.EntryKind

/** 圧縮オプション（SPEC §6.4: zip 既定 / tar.gz / 7z、パスワード・レベル・元を削除） */
@Composable
fun CompressDialog(
    itemCount: Int,
    onApply: (format: CompressFormat, level: Int, password: String?, deleteSource: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var format by remember { mutableStateOf(CompressFormat.ZIP) }
    var level by remember { mutableIntStateOf(2) }
    var password by remember { mutableStateOf("") }
    var deleteSource by remember { mutableStateOf(false) }

    FinderAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.compress_title, itemCount),
        width = 304.dp,
        buttons = listOf(
            FinderAlertButton(
                text = stringResource(R.string.act_compress),
                style = FinderButtonStyle.Default,
            ) { onApply(format, level, password.takeIf { it.isNotEmpty() }, deleteSource) },
            FinderAlertButton(text = stringResource(R.string.cancel), onClick = onDismiss),
        ),
    ) {
        FinderSegmented(
            options = CompressFormat.entries.map { it.extension },
            selectedIndex = format.ordinal,
            onSelect = { format = CompressFormat.entries[it] },
            modifier = Modifier.fillMaxWidth(),
        )
        if (format == CompressFormat.ZIP) {
            // 圧縮レベルとパスワードは zip のみ有効（SPEC §15 #8: 7z 暗号化は未対応）
            val levels = listOf(
                1 to R.string.compress_level_fast,
                2 to R.string.compress_level_normal,
                3 to R.string.compress_level_max,
            )
            Spacer(Modifier.height(12.dp))
            FinderFieldLabel(stringResource(R.string.compress_level))
            FinderSegmented(
                options = levels.map { stringResource(it.second) },
                selectedIndex = levels.indexOfFirst { it.first == level }.coerceAtLeast(0),
                onSelect = { level = levels[it].first },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            FinderTextField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.compress_password_optional),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(4.dp))
        FinderCheckboxRow(
            checked = deleteSource,
            onCheckedChange = { deleteSource = it },
            text = stringResource(R.string.compress_delete_source),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 「オプションを指定して展開」: 文字コードと同名フォルダ有無（SPEC §6.4） */
@Composable
fun ExtractOptionsDialog(
    entryName: String,
    onApply: (encoding: String?, wrapInFolder: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var encoding by remember { mutableStateOf<String?>(null) }
    var wrap by remember { mutableStateOf(true) }
    val encodings = listOf(
        null to R.string.encoding_auto,
        "UTF-8" to R.string.encoding_utf8,
        "Shift_JIS" to R.string.encoding_sjis,
        "EUC-JP" to R.string.encoding_eucjp,
        "CP437" to R.string.encoding_cp437,
    )
    FinderAlertDialog(
        onDismissRequest = onDismiss,
        icon = { EntryKindIcon(kind = EntryKind.ARCHIVE, name = entryName, size = 48.dp) },
        title = entryName,
        buttons = listOf(
            FinderAlertButton(
                text = stringResource(R.string.ctx_extract_short),
                style = FinderButtonStyle.Default,
            ) { onApply(encoding, wrap) },
            FinderAlertButton(text = stringResource(R.string.cancel), onClick = onDismiss),
        ),
    ) {
        FinderFieldLabel(stringResource(R.string.extract_encoding_label))
        // 5 択はセグメントに収まらないため macOS と同じくポップアップボタンにする
        FinderPopUpButton(
            options = encodings.map { stringResource(it.second) },
            selectedIndex = encodings.indexOfFirst { it.first == encoding }.coerceAtLeast(0),
            onSelect = { encoding = encodings[it].first },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        FinderCheckboxRow(
            checked = wrap,
            onCheckedChange = { wrap = it },
            text = stringResource(R.string.extract_wrap_folder),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** アーカイブのパスワード入力（SPEC §6.4: zip AES/ZipCrypto・7z・rar） */
@Composable
fun ArchivePasswordDialog(
    archiveName: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    FinderAlertDialog(
        onDismissRequest = onCancel,
        icon = { EntryKindIcon(kind = EntryKind.ARCHIVE, name = archiveName, size = 48.dp) },
        title = stringResource(R.string.archive_password_title),
        message = stringResource(R.string.archive_password_body, archiveName),
        buttons = listOf(
            FinderAlertButton(
                text = stringResource(R.string.ql_pdf_unlock),
                style = FinderButtonStyle.Default,
            ) { onSubmit(value) },
            FinderAlertButton(text = stringResource(R.string.cancel), onClick = onCancel),
        ),
    ) {
        FinderTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = stringResource(R.string.ql_pdf_password_hint),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
