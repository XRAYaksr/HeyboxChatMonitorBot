package com.heychat.monitor.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heychat.monitor.AppViewModel
import com.heychat.monitor.R
import com.heychat.monitor.data.LiveDataDto
import com.heychat.monitor.data.OnlineUserDto
import com.heychat.monitor.data.RoleDto
import com.heychat.monitor.data.RoomInfo
import com.heychat.monitor.ui.components.CapabilityNote
import com.heychat.monitor.ui.components.EditGate
import com.heychat.monitor.ui.components.EditGateHost
import com.heychat.monitor.ui.components.EmptyState
import com.heychat.monitor.ui.components.LabeledValue
import com.heychat.monitor.ui.components.RemoteImage
import com.heychat.monitor.ui.components.expressivePress
import com.heychat.monitor.ui.components.rememberEditGate

/** 实况卡片：房间语音频道 + 该频道当前在线成员。 */
data class LiveEntry(
    val channelId: String,
    val channelName: String,
    val monitored: Boolean,
    val users: List<OnlineUserDto>,
)

/** 频道实况：哪个频道有哪些人在线，并可就地管理成员身份组。 */
@Composable
fun LiveTab(
    viewModel: AppViewModel,
    onSendToChannel: (String) -> Unit,
    onMentionUser: (OnlineUserDto) -> Unit,
    onViewUserHistory: (String) -> Unit,
) {
    val live by viewModel.live.collectAsStateWithLifecycle()
    val room by viewModel.room.collectAsStateWithLifecycle()
    val gate = rememberEditGate(viewModel)
    var selected by remember { mutableStateOf<OnlineUserDto?>(null) }

    LaunchedEffect(Unit) { if (live == null) viewModel.loadLive() }

    val entries = remember(live, room) { buildLiveEntries(live, room) }
    val recent = live?.recentOnline.orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.live_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                live?.lastUpdate?.let {
                    Text(
                        text = stringResource(R.string.live_last_update, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { viewModel.loadLive() }, modifier = Modifier.expressivePress()) {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.refresh))
                }
            }
        }

        if (entries.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.Groups,
                    text = stringResource(R.string.live_no_data),
                    supporting = stringResource(R.string.live_empty_hint),
                    actionLabel = stringResource(R.string.refresh),
                    onAction = { viewModel.loadAll() },
                )
            }
        }

        items(entries, key = { it.channelId }) { entry ->
            ChannelCard(
                entry = entry,
                onSend = { onSendToChannel(entry.channelId) },
                onUserClick = { selected = it },
            )
        }

        if (recent.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.live_recent_left_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(recent, key = { "recent-" + it.userId }) { item ->
                Card(
                    onClick = { onViewUserHistory(item.userId) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.expressivePress(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RemoteImage(
                            url = item.avatar,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = item.username.ifBlank { stringResource(R.string.unknown_user) },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(R.string.live_ago, item.ago),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Rounded.PersonOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }

    if (selected != null) {
        MemberDialog(
            viewModel = viewModel,
            user = selected!!,
            gate = gate,
            onDismiss = {
                selected = null
                viewModel.clearUserRoles()
            },
            onMention = { user ->
                selected = null
                viewModel.clearUserRoles()
                onMentionUser(user)
            },
            onViewHistory = { userId ->
                selected = null
                viewModel.clearUserRoles()
                onViewUserHistory(userId)
            },
        )
    }
    EditGateHost(gate)
}

/** 房间频道与轮询结果合并：轮询到但房间列表缺失的频道也如实列出。 */
private fun buildLiveEntries(live: LiveDataDto?, room: RoomInfo?): List<LiveEntry> {
    val grouped = live?.online.orEmpty().groupBy { it.channelId }
    val known = room?.channels.orEmpty().filter { it.isVoice }
    val entries = known.map { channel ->
        LiveEntry(
            channelId = channel.channelId,
            channelName = channel.channelName.ifBlank { channel.channelId },
            monitored = channel.monitored,
            users = grouped[channel.channelId].orEmpty(),
        )
    }
    val extra = grouped.keys - known.map { it.channelId }.toSet()
    val monitoredIds = room?.channels.orEmpty().filter { it.monitored }.map { it.channelId }.toSet()
    val unnamed = extra.sorted().map { channelId ->
        LiveEntry(
            channelId = channelId,
            channelName = channelId,
            monitored = channelId in monitoredIds,
            users = grouped[channelId].orEmpty(),
        )
    }
    val all = entries + unnamed
    return all.sortedWith(compareByDescending<LiveEntry> { it.users.isNotEmpty() }.thenBy { it.channelName })
}

@Composable
private fun ChannelCard(entry: LiveEntry, onSend: () -> Unit, onUserClick: (OnlineUserDto) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .expressivePress(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = entry.channelName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (entry.monitored) {
                            stringResource(R.string.live_channel_monitored, entry.users.size)
                        } else {
                            stringResource(R.string.live_channel_headcount, entry.users.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalIconButton(onClick = onSend, modifier = Modifier.expressivePress()) {
                    Icon(Icons.Rounded.Campaign, contentDescription = stringResource(R.string.send_entry))
                }
            }
            Spacer(Modifier.height(8.dp))
            if (entry.users.isEmpty()) {
                Text(
                    text = stringResource(R.string.live_empty_channel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                entry.users.forEach { user ->
                    OnlineUserRow(user = user, onClick = { onUserClick(user) })
                }
            }
        }
    }
}

@Composable
private fun OnlineUserRow(user: OnlineUserDto, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .expressivePress(),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RemoteImage(url = user.avatar, contentDescription = null, modifier = Modifier.size(40.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = user.username.ifBlank { stringResource(R.string.unknown_user) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.member_user_id, user.userId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = user.currentSeconds?.let { formatDuration(it) } ?: "-",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = formatDate(user.joinTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MemberDialog(
    viewModel: AppViewModel,
    user: OnlineUserDto,
    gate: EditGate,
    onDismiss: () -> Unit,
    onMention: (OnlineUserDto) -> Unit,
    onViewHistory: (String) -> Unit,
) {
    val roles by viewModel.roles.collectAsStateWithLifecycle()
    val assigned by viewModel.userRoles.collectAsStateWithLifecycle()
    val channelName = memberChannelName(viewModel, user.channelId)

    LaunchedEffect(user.userId) { viewModel.loadRolesOf(user.userId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RemoteImage(url = user.avatar, contentDescription = null, modifier = Modifier.size(40.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = user.username.ifBlank { stringResource(R.string.unknown_user) },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.member_user_id, user.userId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    LabeledValue(label = stringResource(R.string.member_channel), value = channelName)
                }
                item {
                    LabeledValue(
                        label = stringResource(R.string.member_join_time),
                        value = formatClock(user.joinTime),
                    )
                }
                item {
                    LabeledValue(
                        label = stringResource(R.string.member_online_now),
                        value = user.currentSeconds?.let { formatDuration(it) } ?: "-",
                    )
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.member_roles_title), style = MaterialTheme.typography.titleMedium)
                }
                if (roles.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.member_roles_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(roles, key = { it.id }) { role ->
                    RoleRow(
                        role = role,
                        checked = assigned.contains(role.id),
                        onToggle = { enable ->
                            gate.request { viewModel.setRole(user.userId, role, enable) }
                        },
                    )
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    CapabilityNote(text = stringResource(R.string.member_unsupported_note))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onMention(user) }) {
                Text(stringResource(R.string.member_send_mention))
            }
        },
        dismissButton = {
            TextButton(onClick = { onViewHistory(user.userId) }) {
                Text(stringResource(R.string.member_view_history))
            }
        },
    )
}

@Composable
private fun memberChannelName(viewModel: AppViewModel, channelId: String): String {
    val room by viewModel.room.collectAsStateWithLifecycle()
    return room?.channels.orEmpty().firstOrNull { it.channelId == channelId }?.channelName?.takeIf { it.isNotBlank() }
        ?: channelId
}

@Composable
private fun RoleRow(role: RoleDto, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(parseRoleColor(role.color) ?: MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Text(
            text = role.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Checkbox(checked = checked, onCheckedChange = onToggle)
    }
}
