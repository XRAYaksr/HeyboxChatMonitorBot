package com.heychat.monitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heychat.monitor.AppViewModel
import com.heychat.monitor.ConfigDraft
import com.heychat.monitor.R
import com.heychat.monitor.data.LanguageTag
import com.heychat.monitor.data.PublicConfig
import com.heychat.monitor.data.ThemeTag
import com.heychat.monitor.ui.components.CapabilityNote
import com.heychat.monitor.ui.components.ConfirmDialog
import com.heychat.monitor.ui.components.EditGate
import com.heychat.monitor.ui.components.EditGateHost
import com.heychat.monitor.ui.components.LabeledValue
import com.heychat.monitor.ui.components.SectionHeader
import com.heychat.monitor.ui.components.expressivePress
import com.heychat.monitor.ui.components.rememberEditGate

/**
 * 机器人配置的界面草稿：列表字段以逗号分隔文本编辑，保存时再拆分。
 * 与 [ConfigDraft]（提交给后端的差量）分开，避免把展示格式混进协议。
 */
private data class SettingsForm(
    val pollInterval: String = "",
    val recordInitialOnline: Boolean = false,
    val botEnabled: Boolean = true,
    val channelIds: List<String> = emptyList(),
    val epicChannelId: String = "",
    val epicTimesText: String = "",
    val adminsText: String = "",
    val heyboxId: String = "",
    val roomId: String = "",
    val webPort: String = "",
    val newToken: String = "",
    val newEditPassword: String = "",
)

private fun formOf(config: PublicConfig) = SettingsForm(
    pollInterval = config.pollInterval.toString(),
    recordInitialOnline = config.recordInitialOnline,
    botEnabled = config.botEnabled,
    channelIds = config.channelIds,
    epicChannelId = config.epicPushChannelId,
    epicTimesText = joinList(config.epicPushTimes),
    adminsText = joinList(config.admins),
    heyboxId = config.heyboxId,
    roomId = config.roomId,
    webPort = config.webPort.toString(),
)

private fun SettingsForm.toDraft(defaultPoll: Int) = ConfigDraft(
    pollInterval = (pollInterval.toIntOrNull() ?: defaultPoll).toString(),
    recordInitialOnline = recordInitialOnline,
    botEnabled = botEnabled,
    channelIds = channelIds,
    admins = splitList(adminsText),
    epicChannelId = epicChannelId,
    epicTimes = splitList(epicTimesText),
    heyboxId = heyboxId,
    roomId = roomId,
    webPort = webPort,
    newToken = newToken,
    newEditPassword = newEditPassword,
)

/** 设置页：本机偏好（语言、深浅色、登出）与机器人配置（写操作需独立编辑密码）。 */
@Composable
fun SettingsTab(viewModel: AppViewModel, onLanguageSwitched: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val room by viewModel.room.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val gate = rememberEditGate(viewModel)
    var logout by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (config == null) viewModel.loadConfig()
        if (room == null) viewModel.loadRoom()
    }

    val channels = room?.channels.orEmpty().map { it.channelId to it.channelName.ifBlank { it.channelId } }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_app_group)) {
                ChoiceRow(
                    label = stringResource(R.string.settings_language),
                    supporting = stringResource(R.string.settings_language_desc),
                    options = listOf(
                        LanguageTag.System to stringResource(R.string.settings_language_system),
                        LanguageTag.Chinese to stringResource(R.string.settings_language_zh),
                        LanguageTag.English to stringResource(R.string.settings_language_en),
                    ),
                    selected = settings.languageTag,
                    onSelect = {
                        viewModel.setLanguage(it)
                        onLanguageSwitched()
                    },
                )
                ChoiceRow(
                    label = stringResource(R.string.settings_theme),
                    supporting = stringResource(R.string.settings_theme_desc),
                    options = listOf(
                        ThemeTag.Light to stringResource(R.string.settings_theme_light),
                        ThemeTag.System to stringResource(R.string.settings_theme_system),
                    ),
                    selected = settings.themeTag,
                    onSelect = { viewModel.setTheme(it) },
                )
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_server_group)) {
                LabeledValue(
                    label = stringResource(R.string.settings_server_address),
                    value = settings.server.ifBlank { "-" },
                )
                Spacer(Modifier.height(12.dp))
                LabeledValue(
                    label = stringResource(R.string.cfg_room_id),
                    value = room?.roomId?.takeIf { it.isNotBlank() } ?: config?.roomId ?: "-",
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_logout_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { logout = true }, modifier = Modifier.expressivePress()) {
                        Text(stringResource(R.string.settings_logout), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        val current = config
        item {
            if (current == null) {
                SettingsCard(title = stringResource(R.string.settings_bot_group)) {
                    Text(
                        text = stringResource(R.string.loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { viewModel.loadConfig() }, modifier = Modifier.expressivePress()) {
                        Text(stringResource(R.string.retry))
                    }
                }
            } else {
                BotConfigCard(
                    viewModel = viewModel,
                    config = current,
                    channels = channels,
                    busy = busy,
                    gate = gate,
                )
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_about)) {
                LabeledValue(label = stringResource(R.string.app_name), value = appVersion())
                Spacer(Modifier.height(12.dp))
                CapabilityNote(text = stringResource(R.string.settings_capability_note))
            }
        }
    }

    if (logout) {
        ConfirmDialog(
            title = stringResource(R.string.settings_logout_title),
            body = stringResource(R.string.settings_logout_body),
            confirmLabel = stringResource(R.string.settings_logout),
            destructive = true,
            onConfirm = {
                logout = false
                viewModel.logout()
            },
            onDismiss = { logout = false },
        )
    }
    EditGateHost(gate)
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    supporting: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = supporting,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options, key = { it.second }) { (value, text) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(text) },
                    leadingIcon = if (value == selected) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    val name = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()
    return stringResource(R.string.settings_version, name ?: "-")
}

/**
 * 机器人配置表单：只把与服务器当前值不同的字段写回，掩码 Token 不回传，
 * 新 Token 与编辑密码只在填写时提交。
 */
@Composable
private fun BotConfigCard(
    viewModel: AppViewModel,
    config: PublicConfig,
    channels: List<Pair<String, String>>,
    busy: Boolean,
    gate: EditGate,
) {
    var form by remember(config) { mutableStateOf(formOf(config)) }
    var submitted by remember(config) { mutableStateOf(false) }

    val poll = form.pollInterval.toIntOrNull()
    val pollInvalid = poll == null || poll !in 1..3600
    val timesInvalid = form.epicTimesText.isNotBlank() && !isPushTimeList(form.epicTimesText)
    val channelsInvalid = form.channelIds.isEmpty()
    val valid = !pollInvalid && !timesInvalid && !channelsInvalid

    SettingsCard(title = stringResource(R.string.settings_bot_group)) {
        SectionHeader(stringResource(R.string.cfg_monitor_group))
        NumberField(
            value = form.pollInterval,
            onValueChange = { form = form.copy(pollInterval = it.filter { c -> c.isDigit() }.take(4)) },
            label = stringResource(R.string.cfg_poll_interval),
            supporting = if (submitted && pollInvalid) {
                stringResource(R.string.cfg_invalid_number)
            } else {
                stringResource(R.string.cfg_poll_interval_desc)
            },
            invalid = submitted && pollInvalid,
        )
        SwitchRow(
            label = stringResource(R.string.cfg_record_initial),
            supporting = stringResource(R.string.cfg_record_initial_desc),
            checked = form.recordInitialOnline,
            onChange = { form = form.copy(recordInitialOnline = it) },
        )
        SwitchRow(
            label = stringResource(R.string.cfg_bot_enabled),
            supporting = stringResource(R.string.cfg_bot_enabled_desc),
            checked = form.botEnabled,
            onChange = { form = form.copy(botEnabled = it) },
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.cfg_channels), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = if (channelsInvalid) {
                stringResource(R.string.cfg_no_channels)
            } else {
                stringResource(R.string.cfg_channels_desc)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (channelsInvalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ChannelPicker(
            channels = channels,
            selected = form.channelIds,
            onChange = { form = form.copy(channelIds = it) },
        )
        Spacer(Modifier.height(12.dp))
        SectionHeader(stringResource(R.string.cfg_push_group))
        TextFieldRow(
            value = form.epicTimesText,
            onValueChange = { form = form.copy(epicTimesText = it) },
            label = stringResource(R.string.cfg_epic_times),
            supporting = if (submitted && timesInvalid) {
                stringResource(R.string.cfg_epic_times_invalid)
            } else {
                stringResource(R.string.cfg_epic_times_desc)
            },
            invalid = submitted && timesInvalid,
        )
        Text(stringResource(R.string.cfg_epic_channel), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(R.string.cfg_epic_channel_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ChannelPicker(
            channels = listOf("" to stringResource(R.string.cfg_epic_channel_none)) + channels,
            selected = listOf(form.epicChannelId),
            multiple = false,
            onChange = { ids -> form = form.copy(epicChannelId = ids.firstOrNull().orEmpty()) },
        )
        Spacer(Modifier.height(12.dp))
        SectionHeader(stringResource(R.string.cfg_credentials_group))
        TextFieldRow(
            value = form.adminsText,
            onValueChange = { form = form.copy(adminsText = it) },
            label = stringResource(R.string.cfg_admins),
            supporting = stringResource(R.string.cfg_admins_desc),
        )
        TextFieldRow(
            value = form.heyboxId,
            onValueChange = { form = form.copy(heyboxId = it.filter { c -> c.isDigit() }.take(20)) },
            label = stringResource(R.string.cfg_bot_id),
            supporting = stringResource(R.string.cfg_id_restart_note),
        )
        TextFieldRow(
            value = form.roomId,
            onValueChange = { form = form.copy(roomId = it.filter { c -> c.isDigit() }.take(20)) },
            label = stringResource(R.string.cfg_room_id),
            supporting = stringResource(R.string.cfg_id_restart_note),
        )
        NumberField(
            value = form.webPort,
            onValueChange = { form = form.copy(webPort = it.filter { c -> c.isDigit() }.take(5)) },
            label = stringResource(R.string.cfg_web_port),
            supporting = stringResource(R.string.cfg_web_port_desc),
        )
        TextFieldRow(
            value = form.newToken,
            onValueChange = { form = form.copy(newToken = it) },
            label = stringResource(R.string.cfg_token),
            supporting = stringResource(R.string.cfg_token_desc, config.tokenMasked),
            password = true,
        )
        TextFieldRow(
            value = form.newEditPassword,
            onValueChange = { form = form.copy(newEditPassword = it) },
            label = stringResource(R.string.cfg_edit_password),
            supporting = stringResource(
                if (config.hasEditPassword) R.string.cfg_edit_password_set else R.string.cfg_edit_password_unset,
            ),
            password = true,
        )
        Text(
            text = stringResource(R.string.cfg_web_password_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    submitted = true
                    if (!valid) return@Button
                    gate.request {
                        viewModel.saveConfig(form.toDraft(config.pollInterval), config)
                    }
                },
                enabled = !busy,
                modifier = Modifier
                    .weight(1f)
                    .expressivePress(),
            ) {
                Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(if (busy) stringResource(R.string.loading) else stringResource(R.string.save))
            }
            TextButton(
                onClick = {
                    form = formOf(config)
                    submitted = false
                },
                modifier = Modifier.expressivePress(),
            ) { Text(stringResource(R.string.cfg_reset)) }
        }
        if (channels.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            CapabilityNote(text = stringResource(R.string.cfg_no_channels))
        }
    }
}

@Composable
private fun ChannelPicker(
    channels: List<Pair<String, String>>,
    selected: List<String>,
    multiple: Boolean = true,
    onChange: (List<String>) -> Unit,
) {
    if (channels.isEmpty()) {
        Text(
            text = stringResource(R.string.cfg_no_channels),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(channels, key = { "p-" + it.first }) { (id, name) ->
            val checked = if (multiple) selected.contains(id) else selected.firstOrNull() == id
            FilterChip(
                selected = checked,
                onClick = {
                    onChange(
                        when {
                            multiple && checked -> selected - id
                            multiple -> selected + id
                            checked -> emptyList()
                            else -> listOf(id)
                        },
                    )
                },
                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = if (checked) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, supporting: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TextFieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supporting: String,
    invalid: Boolean = false,
    password: Boolean = false,
    numeric: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = {
            Text(
                text = supporting,
                color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        isError = invalid,
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                numeric -> KeyboardType.Number
                password -> KeyboardType.Password
                else -> KeyboardType.Text
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = MaterialTheme.shapes.small,
    )
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supporting: String,
    invalid: Boolean = false,
) {
    TextFieldRow(
        value = value,
        onValueChange = onValueChange,
        label = label,
        supporting = supporting,
        invalid = invalid,
        numeric = true,
    )
}
