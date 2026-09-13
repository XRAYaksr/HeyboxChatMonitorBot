@file:OptIn(ExperimentalMaterial3Api::class)

package com.heychat.monitor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heychat.monitor.AppViewModel
import com.heychat.monitor.R
import com.heychat.monitor.ui.components.MessageBarHost

enum class MainTab { Room, Live, History, Settings }

/** 主界面：底部 4 项导航栏 + 各导航页内容。 */
@Composable
fun MainScreen(
    viewModel: AppViewModel,
    hostState: SnackbarHostState,
    onLanguageSwitched: () -> Unit,
) {
    val room by viewModel.room.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    var tabIndex by rememberSaveable { mutableIntStateOf(MainTab.Room.ordinal) }
    val current = MainTab.entries[tabIndex]

    var sendChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    var sendAtUserId by rememberSaveable { mutableStateOf<String?>(null) }
    var historyUser by rememberSaveable { mutableStateOf("") }
    var historyChannel by rememberSaveable { mutableStateOf("") }
    var historyReload by rememberSaveable { mutableIntStateOf(0) }

    // 进入主界面时先把所有页面需要的数据拉一遍，避免各页各自为空
    LaunchedEffect(Unit) { viewModel.loadAll() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { MessageBarHost(hostState) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    selected = current == MainTab.Room,
                    onClick = { tabIndex = MainTab.Room.ordinal },
                    icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                    label = {
                        Text(room?.roomName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.nav_room))
                    },
                )
                NavigationBarItem(
                    selected = current == MainTab.Live,
                    onClick = { tabIndex = MainTab.Live.ordinal },
                    icon = { Icon(Icons.Rounded.Mic, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_live)) },
                )
                NavigationBarItem(
                    selected = current == MainTab.History,
                    onClick = {
                        tabIndex = MainTab.History.ordinal
                        historyReload++
                    },
                    icon = { Icon(Icons.Rounded.History, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_history)) },
                )
                NavigationBarItem(
                    selected = current == MainTab.Settings,
                    onClick = { tabIndex = MainTab.Settings.ordinal },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_settings)) },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            when (current) {
                MainTab.Room -> HomeTab(
                    viewModel = viewModel,
                    onOpenSend = {
                        sendAtUserId = null
                        sendChannelId = ""
                    },
                )

                MainTab.Live -> LiveTab(
                    viewModel = viewModel,
                    onSendToChannel = { channelId ->
                        sendAtUserId = null
                        sendChannelId = channelId
                    },
                    onMentionUser = { user ->
                        sendAtUserId = user.userId
                        sendChannelId = user.channelId
                    },
                    onViewUserHistory = { userId ->
                        historyUser = userId
                        historyChannel = ""
                        historyReload++
                        tabIndex = MainTab.History.ordinal
                    },
                )

                MainTab.History -> HistoryTab(
                    viewModel = viewModel,
                    initialUser = historyUser,
                    initialChannel = historyChannel,
                    reloadKey = historyReload,
                )

                MainTab.Settings -> SettingsTab(
                    viewModel = viewModel,
                    onLanguageSwitched = onLanguageSwitched,
                )
            }
        }
    }

    val sendTarget = sendChannelId
    if (sendTarget != null) {
        SendMessageDialog(
            viewModel = viewModel,
            initialChannelId = sendTarget,
            atUser = sendAtUserId?.let { id -> live?.online?.firstOrNull { it.userId == id } },
            onDismiss = {
                sendChannelId = null
                sendAtUserId = null
            },
        )
    }
}
