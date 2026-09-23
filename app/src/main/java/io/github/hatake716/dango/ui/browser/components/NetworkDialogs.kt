package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.db.ConnectionEntity
import io.github.hatake716.dango.data.net.NetProtocol

/** 接続の追加・編集（SPEC §7.2）。秘密鍵認証・mDNS 検出は今後対応 */
@Composable
fun ConnectionDialog(
    initial: ConnectionEntity,
    onSave: (ConnectionEntity, password: String?) -> Unit,
    onTest: (ConnectionEntity, password: String) -> Unit,
    onDelete: (ConnectionEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var protocol by remember { mutableStateOf(NetProtocol.ofName(initial.protocol)) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(if (initial.id == 0L) "" else initial.port.toString()) }
    var sharePath by remember { mutableStateOf(initial.sharePath) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf("") }
    var savePassword by remember { mutableStateOf(initial.savePassword) }

    fun build(): ConnectionEntity = initial.copy(
        name = name.ifBlank { host },
        protocol = protocol.scheme,
        host = host.trim(),
        port = port.toIntOrNull() ?: protocol.defaultPort,
        sharePath = sharePath.trim(),
        username = username.trim(),
        savePassword = savePassword,
    )

    val buttons = buildList {
        add(
            FinderAlertButton(
                text = stringResource(R.string.conn_save),
                style = FinderButtonStyle.Default,
                enabled = host.isNotBlank(),
            ) { onSave(build(), password.takeIf { it.isNotEmpty() }) },
        )
        // テストは結果をスナックバーで知らせるだけでダイアログは閉じない
        add(
            FinderAlertButton(
                text = stringResource(R.string.conn_test),
                enabled = host.isNotBlank(),
                dismisses = false,
            ) { onTest(build(), password) },
        )
        if (initial.id != 0L) {
            add(
                FinderAlertButton(
                    text = stringResource(R.string.conn_delete),
                    style = FinderButtonStyle.Destructive,
                ) { onDelete(initial) },
            )
        }
        add(FinderAlertButton(text = stringResource(R.string.cancel), onClick = onDismiss))
    }

    FinderAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(
            if (initial.id == 0L) R.string.net_add_connection else R.string.conn_save,
        ),
        width = 320.dp,
        buttons = buttons,
    ) {
        FinderSegmented(
            options = NetProtocol.entries.map { it.scheme.uppercase() },
            selectedIndex = protocol.ordinal,
            onSelect = { protocol = NetProtocol.entries[it] },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        FinderTextField(
            value = name,
            onValueChange = { name = it },
            label = stringResource(R.string.conn_name),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            FinderTextField(
                value = host,
                onValueChange = { host = it },
                label = stringResource(R.string.conn_host),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FinderTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() } },
                label = stringResource(R.string.conn_port),
                placeholder = protocol.defaultPort.toString(),
                modifier = Modifier.width(72.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        FinderTextField(
            value = sharePath,
            onValueChange = { sharePath = it },
            label = stringResource(R.string.conn_share),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        FinderTextField(
            value = username,
            onValueChange = { username = it },
            label = stringResource(R.string.conn_username),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        FinderTextField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.conn_password),
            visualTransformation = PasswordVisualTransformation(),
            // パスワード用キーボード（IME に学習させない・予測変換を出さない）
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(2.dp))
        FinderCheckboxRow(
            checked = savePassword,
            onCheckedChange = { savePassword = it },
            text = stringResource(R.string.conn_save_password),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** ネットワークパスワード入力（保存していない接続用） */
@Composable
fun NetPasswordDialog(
    connectionName: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    FinderAlertDialog(
        onDismissRequest = onCancel,
        icon = { FinderAppIcon() },
        title = stringResource(R.string.conn_password),
        message = stringResource(R.string.net_password_body, connectionName),
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
            placeholder = stringResource(R.string.conn_password),
            visualTransformation = PasswordVisualTransformation(),
            // パスワード用キーボード（IME に学習させない・予測変換を出さない）
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
