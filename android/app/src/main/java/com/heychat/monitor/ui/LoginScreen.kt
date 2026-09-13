@file:OptIn(ExperimentalMaterial3Api::class)

package com.heychat.monitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heychat.monitor.AppViewModel
import com.heychat.monitor.R
import com.heychat.monitor.data.ApiClient
import com.heychat.monitor.ui.components.MessageBarHost
import com.heychat.monitor.ui.components.expressivePress

/** 设计稿中的登录屏幕：顶栏 + 两个 outlined 输入框 + 居中偏右的校验按钮 + 底部错误消息条。 */
@Composable
fun LoginScreen(
    viewModel: AppViewModel,
    hostState: SnackbarHostState,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    var server by rememberSaveable { mutableStateOf("") }
    var secret by rememberSaveable { mutableStateOf("") }
    var secretVisible by rememberSaveable { mutableStateOf(false) }
    var touched by rememberSaveable { mutableStateOf(false) }

    // 偏好设置是异步读出的，首次拿到已保存的地址/密钥时回填空白输入框
    LaunchedEffect(settings.server, settings.secret) {
        if (server.isBlank()) server = settings.server
        if (secret.isBlank()) secret = settings.secret
    }

    val serverInvalid = touched && ApiClient.normalizeBase(server) == null
    val secretInvalid = touched && secret.isBlank()

    fun submit() {
        touched = true
        if (ApiClient.normalizeBase(server) == null || secret.isBlank()) return
        viewModel.login(server, secret)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.login_title), style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = server,
                    onValueChange = {
                        server = it
                        touched = true
                    },
                    label = { Text(stringResource(R.string.login_server_ip)) },
                    placeholder = { Text(stringResource(R.string.login_server_hint)) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Language, contentDescription = null)
                    },
                    singleLine = true,
                    isError = serverInvalid,
                    supportingText = if (serverInvalid) {
                        { Text(stringResource(R.string.login_invalid_server)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = {
                        secret = it
                        touched = true
                    },
                    label = { Text(stringResource(R.string.login_secret)) },
                    placeholder = { Text(stringResource(R.string.login_secret_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { secretVisible = !secretVisible }) {
                            Icon(
                                imageVector = if (secretVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = stringResource(
                                    if (secretVisible) R.string.hide_password else R.string.show_password
                                ),
                            )
                        }
                    },
                    singleLine = true,
                    isError = secretInvalid,
                    supportingText = if (secretInvalid) {
                        { Text(stringResource(R.string.login_empty_secret)) }
                    } else {
                        null
                    },
                    visualTransformation = if (secretVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(if (busy) R.string.login_connecting else R.string.login_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(16.dp))
                    FilledTonalIconButton(
                        onClick = { submit() },
                        enabled = !busy,
                        modifier = Modifier.expressivePress(),
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.login_action))
                    }
                }
            }

            MessageBarHost(
                hostState = hostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
            )
        }
    }
}
