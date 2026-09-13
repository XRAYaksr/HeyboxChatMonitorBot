package com.heychat.monitor.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "heychat_settings")

/** zh / en / system，与 strings.xml 的 values、values-en 目录对应。 */
enum class LanguageTag(val tag: String) {
    System("system"), Chinese("zh"), English("en");

    companion object {
        fun of(value: String?): LanguageTag = entries.firstOrNull { it.tag == value } ?: System
    }
}

enum class ThemeTag(val tag: String) {
    Light("light"), Dark("dark"), System("system");

    companion object {
        fun of(value: String?): ThemeTag = entries.firstOrNull { it.tag == value } ?: Light
    }
}

data class AppSettings(
    val server: String = "",
    val secret: String = "",
    val token: String = "",
    val language: String = LanguageTag.System.tag,
    val theme: String = ThemeTag.Light.tag,
    val editPassword: String = "",
) {
    val languageTag: LanguageTag get() = LanguageTag.of(language)
    val themeTag: ThemeTag get() = ThemeTag.of(theme)
    val signedIn: Boolean get() = server.isNotBlank() && token.isNotBlank()
}

/** 设备本地偏好与登录凭据。token/secret 只存私有 SharedPreferences，已排除云备份。 */
class SettingsStore(context: Context) {

    private val store = context.applicationContext.dataStore

    private object Keys {
        val SERVER = stringPreferencesKey("server")
        val SECRET = stringPreferencesKey("secret")
        val TOKEN = stringPreferencesKey("token")
        val LANGUAGE = stringPreferencesKey("language")
        val THEME = stringPreferencesKey("theme")
        val EDIT_PASSWORD = stringPreferencesKey("edit_password")
    }

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            server = prefs[Keys.SERVER] ?: "",
            secret = prefs[Keys.SECRET] ?: "",
            token = prefs[Keys.TOKEN] ?: "",
            language = prefs[Keys.LANGUAGE] ?: LanguageTag.System.tag,
            theme = prefs[Keys.THEME] ?: ThemeTag.Light.tag,
            editPassword = prefs[Keys.EDIT_PASSWORD] ?: "",
        )
    }

    suspend fun snapshot(): AppSettings = settings.first()

    suspend fun setServer(value: String) = set(Keys.SERVER, value)
    suspend fun setSecret(value: String) = set(Keys.SECRET, value)
    suspend fun setToken(value: String) = set(Keys.TOKEN, value)
    suspend fun setLanguage(value: String) = set(Keys.LANGUAGE, value)
    suspend fun setTheme(value: String) = set(Keys.THEME, value)
    suspend fun setEditPassword(value: String) = set(Keys.EDIT_PASSWORD, value)

    /** 退出登录：清掉令牌与二级密码，保留服务器地址和密钥便于再次登录。 */
    suspend fun clearSession() {
        setToken("")
        setEditPassword("")
    }

    private suspend fun set(key: Preferences.Key<String>, value: String) {
        store.edit { it[key] = value }
    }
}
