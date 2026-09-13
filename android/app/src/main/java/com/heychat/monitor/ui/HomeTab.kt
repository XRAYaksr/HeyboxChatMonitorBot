@file:OptIn(ExperimentalMaterial3Api::class)

package com.heychat.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heychat.monitor.AppViewModel
import com.heychat.monitor.R
import com.heychat.monitor.data.RoomInfo
import com.heychat.monitor.data.StatusDto
import com.heychat.monitor.ui.components.LabeledValue
import com.heychat.monitor.ui.components.RemoteImage
import com.heychat.monitor.ui.components.expressivePress

/** 首页：房间卡片（188dp）+ 机器人运行状态卡片 + 快捷操作。 */
@Composable
fun HomeTab(viewModel: AppViewModel, onOpenSend: () -> Unit) {
    val room by viewModel.room.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val roomError by viewModel.roomError.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { RoomCard(room = room, error = roomError, onRetry = { viewModel.loadRoom() }) }
        item { BotStatusCard(status = status, onRetry = { viewModel.loadStatus() }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onOpenSend,
                    modifier = Modifier
                        .weight(1f)
                        .expressivePress(),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Icon(Icons.Rounded.Campaign, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.send_entry))
                }
                FilledTonalButton(
                    onClick = { viewModel.loadAll() },
                    modifier = Modifier.expressivePress(),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun RoomCard(room: RoomInfo?, error: String?, onRetry: () -> Unit) {
    Card(
        onClick = onRetry,
        modifier = Modifier
            .fillMaxWidth()
            .height(188.dp)
            .expressivePress(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Row(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = room?.roomName?.takeIf { it.isNotBlank() }
                            ?: stringResource(if (error != null) R.string.room_load_failed else R.string.loading),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = room?.roomIntro?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.room_no_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column {
                    if (room != null) {
                        Text(
                            text = stringResource(
                                R.string.room_not_monitored_channel,
                                room.channels.count { it.monitored },
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        text = stringResource(R.string.room_click_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 右侧 156dp 全高图片区：图片与渐变遮罩叠放
            Box(
                modifier = Modifier
                    .width(156.dp)
                    .fillMaxHeight(),
            ) {
                RemoteImage(
                    url = room?.roomAvatar.orEmpty(),
                    contentDescription = stringResource(R.string.room_avatar_desc),
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(
                        topEnd = 20.dp,
                        bottomEnd = 20.dp,
                        topStart = 0.dp,
                        bottomStart = 0.dp,
                    ),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f),
                                ),
                            )
                        ),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    if (error != null) {
                        Text(
                            text = stringResource(R.string.room_load_failed),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier
                                .padding(8.dp)
                                .background(
                                    MaterialTheme.colorScheme.errorContainer,
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BotStatusCard(status: StatusDto?, onRetry: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .expressivePress(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        onClick = onRetry,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.bot_status_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(status)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = status?.let { stringResource(R.string.bot_status_running, uptimeOf(it)) }
                    ?: stringResource(R.string.loading),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (status != null) {
                Spacer(Modifier.height(12.dp))
                LabeledValue(
                    label = stringResource(R.string.bot_online_now),
                    value = stringResource(R.string.room_online_now, status.onlineCount),
                )
                LabeledValue(
                    label = stringResource(R.string.bot_last_poll),
                    value = formatClock(status.lastUpdate),
                )
                LabeledValue(
                    label = stringResource(R.string.bot_poll_interval),
                    value = stringResource(R.string.bot_poll_interval_value, status.pollInterval),
                )
                LabeledValue(
                    label = stringResource(R.string.bot_command_channel),
                    value = stringResource(
                        when {
                            !status.botEnabled -> R.string.bot_ws_disabled
                            status.botConnected -> R.string.bot_ws_connected
                            else -> R.string.bot_ws_disconnected
                        }
                    ),
                    valueColor = when {
                        !status.botEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
                        status.botConnected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.error
                    },
                )
                if (status.channelErrors.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    status.channelErrors.forEach { (channelId, message) ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.bot_error_detail, channelId, message),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: StatusDto?) {
    val running = status?.running == true
    val label = when {
        status == null -> stringResource(R.string.loading)
        running && status.channelErrors.isEmpty() -> stringResource(R.string.bot_status_normal)
        running -> stringResource(R.string.bot_status_degraded)
        else -> stringResource(R.string.bot_status_stopped)
    }
    Row(
        modifier = Modifier
            .background(
                if (running) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (running) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (running) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            },
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = if (running) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            },
        )
    }
}

private fun uptimeOf(status: StatusDto): String =
    status.uptimeText.ifBlank { formatUptime(status.uptimeSeconds) }
