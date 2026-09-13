package com.heychat.monitor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heychat.monitor.AuthState
import com.heychat.monitor.AppViewModel
import com.heychat.monitor.R
import com.heychat.monitor.ui.theme.enterFromLeft
import com.heychat.monitor.ui.theme.enterFromRight
import com.heychat.monitor.ui.theme.exitToLeft
import com.heychat.monitor.ui.theme.exitToRight

/**
 * 两个屏幕的宿主：登录与主界面。登录成功后主界面从右侧滑入，
 * 返回/登出时反向播放（主界面向右滑出、登录从左侧滑入）。
 */
@Composable
fun Root(viewModel: AppViewModel, onLanguageSwitched: () -> Unit) {
    val hostState = remember { SnackbarHostState() }
    val auth by viewModel.authState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(hostState) {
        viewModel.toast.collect { toast ->
            val message = if (toast.isError) {
                context.getString(R.string.error_prefix, toast.text)
            } else {
                toast.text
            }
            hostState.showSnackbar(message = message, withDismissAction = toast.isError)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        val signedIn = auth == AuthState.SignedIn
        AnimatedVisibility(
            visible = auth == AuthState.Checking,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
        }
        AnimatedVisibility(
            visible = !signedIn && auth != AuthState.Checking,
            enter = enterFromLeft(),
            exit = exitToRight(),
        ) {
            LoginScreen(viewModel = viewModel, hostState = hostState)
        }
        AnimatedVisibility(
            visible = signedIn,
            enter = enterFromRight(),
            exit = exitToLeft(),
        ) {
            MainScreen(
                viewModel = viewModel,
                hostState = hostState,
                onLanguageSwitched = onLanguageSwitched,
            )
        }
    }
}
