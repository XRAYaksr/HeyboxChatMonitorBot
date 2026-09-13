package com.heychat.monitor

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.heychat.monitor.data.LanguageTag
import com.heychat.monitor.data.SettingsStore
import com.heychat.monitor.data.ThemeTag
import com.heychat.monitor.ui.Root
import com.heychat.monitor.ui.theme.HeyChatTheme
import com.heychat.monitor.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

class MainActivity : ComponentActivity() {

    /** 语言在 attach 阶段确定，切换语言后由 recreate() 重放整棵组件树。 */
    override fun attachBaseContext(newBase: Context) {
        val language = runBlocking { SettingsStore(newBase).settings.first().language }
        super.attachBaseContext(newBase.withLanguage(language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { HeyChatApp(onLanguageSwitched = ::recreate) }
    }
}

private fun Context.withLanguage(tag: String): Context {
    val language = LanguageTag.of(tag)
    if (language == LanguageTag.System) return this
    val locale = when (language) {
        LanguageTag.Chinese -> Locale.SIMPLIFIED_CHINESE
        LanguageTag.English -> Locale.ENGLISH
        LanguageTag.System -> Locale.getDefault()
    }
    val configuration = Configuration(resources.configuration).apply { setLocale(locale) }
    return createConfigurationContext(configuration)
}

@Composable
fun HeyChatApp(
    onLanguageSwitched: () -> Unit,
    viewModel: AppViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    HeyChatTheme(themeMode = settings.themeTag.toMode()) {
        Root(viewModel = viewModel, onLanguageSwitched = onLanguageSwitched)
    }
}

private fun ThemeTag.toMode(): ThemeMode = when (this) {
    ThemeTag.Light -> ThemeMode.Light
    ThemeTag.Dark -> ThemeMode.Dark
    ThemeTag.System -> ThemeMode.System
}
