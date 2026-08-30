package io.github.hatake716.dango.ui.browser.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hatake716.dango.R
import io.github.hatake716.dango.data.db.ConnectionEntity
import io.github.hatake716.dango.data.net.NetProtocol
import io.github.hatake716.dango.ui.theme.DangoTheme

/** 接続の追加・編集（SPEC §7.2）。秘密鍵認証・mDNS 検出は今後対応 */
@Composable
fun ConnectionDialog(
    initial: ConnectionEntity,
    onSave: (ConnectionEntity, password: String?) -> Unit,
    onTest: (ConnectionEntity, password: String) -> Unit,
    onDelete: (ConnectionEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DangoTheme.colors
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial.id == 0L) R.string.net_add_connection else R.string.conn_save,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row {
                    NetProtocol.entries.forEach { p ->
                        FilterChip(
                            selected = protocol == p,
                            onClick = { protocol = p },
                            label = { Text(p.scheme.uppercase(), fontSize = 11.sp) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.conn_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text(stringResource(R.string.conn_host)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.conn_port)) },
                        placeholder = { Text(protocol.defaultPort.toString()) },
                        singleLine = true,
                        modifier = Modifier.width(96.dp),
                    )
                }
                OutlinedTextField(
                    value = sharePath,
                    onValueChange = { sharePath = it },
                    label = { Text(stringResource(R.string.conn_share)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.conn_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.conn_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = savePassword, onCheckedChange = { savePassword = it })
                    Text(stringResource(R.string.conn_save_password), fontSize = 13.sp)
                }
                if (initial.id != 0L) {
                    TextButton(onClick = { onDelete(initial) }) {
                        Text(
                            stringResource(R.string.conn_delete),
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onTest(build(), password) },
                enabled = host.isNotBlank(),
            ) { Text(stringResource(R.string.conn_test)) }
            TextButton(
                onClick = { onSave(build(), password.takeIf { it.isNotEmpty() }) },
                enabled = host.isNotBlank(),
            ) { Text(stringResource(R.string.conn_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** ネットワークパスワード入力（保存していない接続用） */
@Composable
fun NetPasswordDialog(
    connectionName: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.conn_password)) },
        text = {
            Column {
                Text(stringResource(R.string.net_password_body, connectionName))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.conn_password)) },
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
