package com.heychat.monitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heychat.monitor.AppViewModel
import com.heychat.monitor.R
import com.heychat.monitor.data.OnlineUserDto
import com.heychat.monitor.ui.components.EmptyState
import com.heychat.monitor.ui.components.expressivePress

/** 消息长度上限，与后端 SEND_MSG_MAX_LENGTH 保持一致。 */
private const val MESSAGE_MAX_LENGTH = 2000

/**
 * 发送频道消息。atUser 非空时使用平台的 @ 消息类型，由后端决定 msg_type。
 */
@Composable
fun SendMessageDialog(
    viewModel: AppViewModel,
    initialChannelId: String,
    atUser: OnlineUserDto? = null,
    onDismiss: () -> Unit,
) {
    val room by viewModel.room.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val channels = room?.channels.orEmpty()
    var channelId by rememberSaveable(atUser?.channelId) {
        mutableStateOf(initialChannelId.ifBlank { atUser?.channelId.orEmpty() })
    }
    var message by rememberSaveable { mutableStateOf("") }
    var touched by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (channels.isEmpty()) viewModel.loadRoom() }

    val effectiveChannelId = channelId.ifBlank { channels.firstOrNull()?.channelId.orEmpty() }
    val emptyMessage = message.isBlank()
    val tooLong = message.length > MESSAGE_MAX_LENGTH
    val invalidChannel = effectiveChannelId.isBlank()
    val canSend = !emptyMessage && !tooLong && !invalidChannel && !busy

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.send_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (atUser != null) {
                    Text(
                        text = stringResource(R.string.send_mentioning, atUser.username.ifBlank { atUser.userId }),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (channels.isEmpty()) {
                    EmptyState(
                        icon = Icons.Rounded.ErrorOutline,
                        text = stringResource(R.string.send_no_channel),
                        supporting = stringResource(R.string.room_load_failed),
                        actionLabel = stringResource(R.string.refresh),
                        onAction = { viewModel.loadRoom() },
                    )
                } else {
                    Text(
                        text = stringResource(R.string.send_channel),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(channels, key = { it.channelId }) { channel ->
                            FilterChip(
                                selected = channel.channelId == effectiveChannelId,
                                onClick = { channelId = channel.channelId },
                                label = {
                                    Text(
                                        text = channel.channelName.ifBlank { channel.channelId },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                    if (touched && invalidChannel) {
                        Text(
                            text = stringResource(R.string.send_no_channel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text(stringResource(R.string.send_content)) },
                    placeholder = { Text(stringResource(R.string.send_content_hint)) },
                    supportingText = {
                        Text(
                            text = if (tooLong) {
                                stringResource(R.string.send_too_long, message.length, MESSAGE_MAX_LENGTH)
                            } else {
                                stringResource(R.string.send_counter, message.length, MESSAGE_MAX_LENGTH)
                            },
                            color = if (tooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    isError = tooLong,
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    shape = MaterialTheme.shapes.small,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    touched = true
                    if (!canSend) return@Button
                    viewModel.sendMessage(effectiveChannelId, message.trim(), atUser?.userId.orEmpty()) {
                        message = ""
                        onDismiss()
                    }
                },
                enabled = !busy,
                modifier = Modifier.expressivePress(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Send,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(if (busy) stringResource(R.string.loading) else stringResource(R.string.send_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
