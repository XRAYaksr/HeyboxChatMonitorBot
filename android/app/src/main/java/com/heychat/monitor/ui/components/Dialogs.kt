@file:OptIn(ExperimentalMaterial3Api::class)

package com.heychat.monitor.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.heychat.monitor.AppViewModel
import com.heychat.monitor.R

/**
 * 编辑密码闸门：写操作（改身份组、改配置、改/删记录）需要独立二级密码。
 * 本机已缓存时直接执行，否则先弹窗取密码再执行挂起动作。
 */
class EditGate internal constructor(private val viewModel: AppViewModel) {
    var pending by mutableStateOf<(() -> Unit)?>(null)

    fun request(action: () -> Unit) {
        if (viewModel.hasEditPassword()) {
            action()
        } else {
            pending = action
        }
    }

    fun submit(password: String) {
        val action = pending
        pending = null
        if (password.isBlank()) return
        viewModel.provideEditPassword(password)
        action?.invoke()
    }

    fun dismiss() {
        pending = null
    }
}

@Composable
fun rememberEditGate(viewModel: AppViewModel): EditGate = remember(viewModel) { EditGate(viewModel) }

/** 在屏幕内容末尾调用一次，用于渲染编辑密码弹窗。 */
@Composable
fun EditGateHost(gate: EditGate) {
    if (gate.pending != null) {
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { gate.dismiss() },
            icon = { Icon(Icons.Rounded.Info, contentDescription = null) },
            title = { Text(stringResource(R.string.edit_password_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.edit_password_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text(stringResource(R.string.edit_password_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { gate.submit(value) }, enabled = value.isNotBlank()) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { gate.dismiss() }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** 破坏性操作的确认框。 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** 平台能力提示：黑盒机器人接口不存在的操作不做伪装实现。 */
@Composable
fun CapabilityNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
