package com.heychat.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heychat.monitor.AppViewModel
import com.heychat.monitor.R
import com.heychat.monitor.data.HistoryItemDto
import com.heychat.monitor.data.UserSummaryDto
import com.heychat.monitor.ui.components.CapabilityNote
import com.heychat.monitor.ui.components.ConfirmDialog
import com.heychat.monitor.ui.components.EditGate
import com.heychat.monitor.ui.components.EditGateHost
import com.heychat.monitor.ui.components.EmptyState
import com.heychat.monitor.ui.components.LabeledValue
import com.heychat.monitor.ui.components.RemoteImage
import com.heychat.monitor.ui.components.expressivePress
import com.heychat.monitor.ui.components.rememberEditGate

/** 在线记录：进出时间、单次时长与按人累计时长，支持筛选与订正。 */
@Composable
fun HistoryTab(
    viewModel: AppViewModel,
    initialUser: String,
    initialChannel: String,
    reloadKey: Int,
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val room by viewModel.room.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val gate = rememberEditGate(viewModel)

    var userQuery by rememberSaveable(reloadKey) { mutableStateOf(initialUser) }
    var channelQuery by rememberSaveable(reloadKey) { mutableStateOf(initialChannel) }
    var dateQuery by rememberSaveable(reloadKey) { mutableStateOf("") }
    var archivedIncluded by rememberSaveable { mutableStateOf(true) }
    var pendingDelete by remember { mutableStateOf<HistoryItemDto?>(null) }
    var editing by remember { mutableStateOf<HistoryItemDto?>(null) }

    val channels = room?.channels.orEmpty()
    val names = remember(channels) { channels.associate { it.channelId to it.channelName.ifBlank { it.channelId } } }
    val records = history?.items.orEmpty()
    val total = history?.total ?: 0
    val canLoadMore = history != null && records.size < total

    LaunchedEffect(reloadKey, initialUser, initialChannel) {
        viewModel.loadHistory(user = initialUser, channel = initialChannel, date = "", offset = 0)
    }

    fun applyFilters(offset: Int = 0) {
        viewModel.loadHistory(userQuery, channelQuery, dateQuery, offset = offset, archived = archivedIncluded)
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.history_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                if (total > 0) {
                    Text(
                        text = stringResource(R.string.history_total, total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { applyFilters() }, modifier = Modifier.expressivePress()) {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.refresh))
                }
            }
        }

        item {
            FilterCard(
                userQuery = userQuery,
                channelQuery = channelQuery,
                dateQuery = dateQuery,
                archivedIncluded = archivedIncluded,
                channels = channels.map { it.channelId to it.channelName.ifBlank { it.channelId } },
                invalidDate = dateQuery.isNotBlank() && !isPlainDate(dateQuery),
                onUserChange = { userQuery = it },
                onChannelChange = { channelQuery = it },
                onDateChange = { dateQuery = it },
                onArchivedChange = { archivedIncluded = it },
                onApply = { applyFilters() },
                onClear = {
                    userQuery = ""
                    channelQuery = ""
                    dateQuery = ""
                    archivedIncluded = true
                    viewModel.loadHistory("", "", "", offset = 0, archived = true)
                },
            )
        }

        if (busy && records.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        if (!busy && history != null && records.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.PersonSearch,
                    text = stringResource(R.string.history_empty),
                    supporting = stringResource(R.string.history_empty_hint),
                )
            }
        }

        if (records.isNotEmpty()) {
            item {
                SummaryCard(history?.summary.orEmpty(), names) { userId ->
                    userQuery = userId
                    applyFilters()
                }
            }
        }

        items(records, key = { "${if (it.archived) "a" else "s"}-${it.id}" }) { record ->
            HistoryRow(
                record = record,
                channelName = names[record.channelId] ?: record.channelId,
                onFilterUser = {
                    userQuery = record.userId
                    applyFilters()
                },
                onEdit = { editing = record },
                onDelete = { pendingDelete = record },
            )
        }

        if (canLoadMore) {
            item {
                Button(
                    onClick = { applyFilters(offset = records.size) },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .expressivePress(),
                ) {
                    Text(if (busy) stringResource(R.string.loading) else stringResource(R.string.load_more))
                }
            }
        }

        item { CapabilityNote(text = stringResource(R.string.history_retention_note)) }
    }

    editing?.let { record ->
        RecordEditDialog(
            viewModel = viewModel,
            record = record,
            gate = gate,
            onDismiss = { editing = null },
        )
    }
    pendingDelete?.let { record ->
        ConfirmDialog(
            title = stringResource(R.string.history_delete_title),
            body = stringResource(R.string.history_delete_body),
            confirmLabel = stringResource(R.string.history_delete),
            destructive = true,
            onConfirm = {
                pendingDelete = null
                gate.request { viewModel.deleteRecord(record.id) }
            },
            onDismiss = { pendingDelete = null },
        )
    }
    EditGateHost(gate)
}

@Composable
private fun FilterCard(
    userQuery: String,
    channelQuery: String,
    dateQuery: String,
    archivedIncluded: Boolean,
    channels: List<Pair<String, String>>,
    invalidDate: Boolean,
    onUserChange: (String) -> Unit,
    onChannelChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onArchivedChange: (Boolean) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = userQuery,
                onValueChange = onUserChange,
                label = { Text(stringResource(R.string.history_filter_user)) },
                leadingIcon = { Icon(Icons.Rounded.PersonSearch, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onApply() }),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            )
            if (channels.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = channelQuery.isBlank(),
                            onClick = { onChannelChange("") },
                            label = { Text(stringResource(R.string.history_filter_channel)) },
                        )
                    }
                    items(channels, key = { "f-" + it.first }) { (id, name) ->
                        FilterChip(
                            selected = channelQuery == id,
                            onClick = { onChannelChange(if (channelQuery == id) "" else id) },
                            label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = dateQuery,
                    onValueChange = onDateChange,
                    label = { Text(stringResource(R.string.history_filter_date)) },
                    supportingText = {
                        Text(
                            text = if (invalidDate) {
                                stringResource(R.string.history_invalid_date)
                            } else {
                                stringResource(R.string.history_date_hint)
                            },
                            color = if (invalidDate) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    isError = invalidDate,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                )
                FilledTonalIconButton(
                    onClick = { onDateChange(todayDateString()) },
                    modifier = Modifier.expressivePress(),
                ) {
                    Icon(Icons.Rounded.History, contentDescription = stringResource(R.string.history_date_today))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onArchivedChange(!archivedIncluded); onApply() }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.history_include_archived),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = archivedIncluded, onCheckedChange = { onArchivedChange(it); onApply() })
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onApply,
                    enabled = !invalidDate,
                    modifier = Modifier
                        .weight(1f)
                        .expressivePress(),
                ) { Text(stringResource(R.string.search)) }
                TextButton(onClick = onClear, modifier = Modifier.expressivePress()) {
                    Text(stringResource(R.string.clear))
                }
            }
        }
    }
}

/** 累计在线时长：与列表共用同一套筛选条件，点击一行即按该用户过滤。 */
@Composable
private fun SummaryCard(
    summary: List<UserSummaryDto>,
    names: Map<String, String>,
    onPick: (String) -> Unit,
) {
    val ranked = summary.sortedByDescending { it.totalSeconds }.take(8)
    if (ranked.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.history_summary), style = MaterialTheme.typography.titleMedium)
            ranked.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onPick(entry.userId) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (entry.avatar.isNotBlank()) {
                        RemoteImage(url = entry.avatar, contentDescription = null, modifier = Modifier.size(32.dp))
                    } else {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = (entry.username.ifBlank { entry.userId }).take(1),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = entry.username.ifBlank { names[entry.userId] ?: entry.userId },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.history_sessions, entry.sessions),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = entry.totalText.ifBlank { formatDuration(entry.totalSeconds) },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    record: HistoryItemDto,
    channelName: String,
    onFilterUser: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onFilterUser,
        modifier = Modifier
            .fillMaxWidth()
            .expressivePress(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RemoteImage(url = record.avatar, contentDescription = null, modifier = Modifier.size(44.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = record.username.ifBlank { record.userId },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (record.isOpen) {
                        stringResource(R.string.history_join, channelName)
                    } else {
                        stringResource(R.string.history_leave, channelName)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${formatClock(record.joinTime)} → ${formatClock(record.leaveTime)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = record.duration.ifBlank { formatDuration(record.durationSeconds) },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (record.isOpen) {
                        Text(
                            text = stringResource(R.string.history_ongoing),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (record.archived) {
                        Icon(
                            Icons.Rounded.Archive,
                            contentDescription = stringResource(R.string.history_archived),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.expressivePress()) {
                Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.history_edit))
            }
            IconButton(onClick = onDelete, modifier = Modifier.expressivePress()) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.history_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RecordEditDialog(
    viewModel: AppViewModel,
    record: HistoryItemDto,
    gate: EditGate,
    onDismiss: () -> Unit,
) {
    var join by rememberSaveable(record.id) { mutableStateOf(record.joinTime.orEmpty().dropLast(3)) }
    var leave by rememberSaveable(record.id) { mutableStateOf(record.leaveTime.orEmpty().dropLast(3)) }
    var submitted by rememberSaveable(record.id) { mutableStateOf(false) }
    val joinValue = toServerTime(join)
    val leaveValue = toServerTime(leave)
    val valid = joinValue != null && leaveValue != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_edit_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledValue(
                    label = stringResource(R.string.member_user_id, record.userId),
                    value = record.username.ifBlank { "-" },
                )
                TimeField(
                    value = join,
                    onValueChange = { join = it },
                    label = stringResource(R.string.history_edit_join),
                    showError = submitted && joinValue == null,
                )
                TimeField(
                    value = leave,
                    onValueChange = { leave = it },
                    label = stringResource(R.string.history_edit_leave),
                    showError = submitted && leaveValue == null,
                )
                Spacer(Modifier.height(4.dp))
                CapabilityNote(text = stringResource(R.string.history_edit_requires_password))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    submitted = true
                    if (!valid) return@Button
                    gate.request {
                        viewModel.saveRecord(record.id, joinValue.orEmpty(), leaveValue.orEmpty()) { onDismiss() }
                    }
                },
                modifier = Modifier.expressivePress(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun TimeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    showError: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = {
            Text(
                text = if (showError) {
                    stringResource(R.string.history_invalid_time)
                } else {
                    stringResource(R.string.history_edit_hint)
                },
                color = if (showError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        isError = showError,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
    )
}
