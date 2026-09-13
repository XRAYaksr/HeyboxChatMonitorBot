package com.heychat.monitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.heychat.monitor.data.ApiClient
import com.heychat.monitor.data.ApiException
import com.heychat.monitor.data.AppSettings
import com.heychat.monitor.data.HistoryDto
import com.heychat.monitor.data.LanguageTag
import com.heychat.monitor.data.LiveDataDto
import com.heychat.monitor.data.NetworkException
import com.heychat.monitor.data.PublicConfig
import com.heychat.monitor.data.RoleDto
import com.heychat.monitor.data.RoomInfo
import com.heychat.monitor.data.SettingsStore
import com.heychat.monitor.data.StatusDto
import com.heychat.monitor.data.ThemeTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class AuthState { Checking, SignedOut, SignedIn }

/** 一次性提示（成功/失败）。 */
data class Toast(val text: String, val isError: Boolean = false)

/** 机器人设置表单的草稿，提交时只把与服务器值不同的字段写回。 */
data class ConfigDraft(
    val pollInterval: String = "",
    val recordInitialOnline: Boolean = false,
    val botEnabled: Boolean = true,
    val channelIds: List<String> = emptyList(),
    val admins: List<String> = emptyList(),
    val epicChannelId: String = "",
    val epicTimes: List<String> = emptyList(),
    val heyboxId: String = "",
    val roomId: String = "",
    val webPort: String = "",
    val newToken: String = "",
    val newEditPassword: String = "",
)

/**
 * 全局状态与后端交互。写操作完成后立即回读受影响的数据，
 * 让界面反映服务器真实状态，而不是本地的乐观假设。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private val api = ApiClient()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _authState = MutableStateFlow(AuthState.Checking)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _status = MutableStateFlow<StatusDto?>(null)
    val status: StateFlow<StatusDto?> = _status.asStateFlow()

    private val _room = MutableStateFlow<RoomInfo?>(null)
    val room: StateFlow<RoomInfo?> = _room.asStateFlow()

    private val _roomError = MutableStateFlow<String?>(null)
    val roomError: StateFlow<String?> = _roomError.asStateFlow()

    private val _live = MutableStateFlow<LiveDataDto?>(null)
    val live: StateFlow<LiveDataDto?> = _live.asStateFlow()

    private val _history = MutableStateFlow<HistoryDto?>(null)
    val history: StateFlow<HistoryDto?> = _history.asStateFlow()

    private val _config = MutableStateFlow<PublicConfig?>(null)
    val config: StateFlow<PublicConfig?> = _config.asStateFlow()

    private val _roles = MutableStateFlow<List<RoleDto>>(emptyList())
    val roles: StateFlow<List<RoleDto>> = _roles.asStateFlow()

    private val _userRoles = MutableStateFlow<Set<String>>(emptySet())
    val userRoles: StateFlow<Set<String>> = _userRoles.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _toast = MutableSharedFlow<Toast>(extraBufferCapacity = 4)
    val toast: SharedFlow<Toast> = _toast.asSharedFlow()

    init {
        viewModelScope.launch {
            store.settings.collect { saved ->
                _settings.value = saved
                syncConnection(saved)
            }
        }
        restoreSession()
    }

    private fun syncConnection(saved: AppSettings) {
        val base = ApiClient.normalizeBase(saved.server) ?: ""
        api.baseUrl = base
        api.token = if (base.isEmpty()) "" else saved.token
    }

    // ------------------------------------------------------------ 会话与登录

    private fun restoreSession() {
        viewModelScope.launch {
            val saved = store.snapshot()
            _settings.value = saved
            syncConnection(saved)
            if (!saved.signedIn || api.baseUrl.isEmpty()) {
                _authState.value = AuthState.SignedOut
                return@launch
            }
            try {
                _status.value = api.session().status
                _authState.value = AuthState.SignedIn
                loadAll()
            } catch (exc: Exception) {
                if (exc is CancellationException) throw exc
                _authState.value = AuthState.SignedOut
                if (exc is ApiException && exc.status == 401) store.clearSession()
                _toast.emit(Toast(readableError(exc), true))
            }
        }
    }

    fun login(server: String, secret: String) {
        if (ApiClient.normalizeBase(server) == null) {
            _toast.tryEmit(Toast(tr(R.string.login_invalid_server), true))
            return
        }
        if (secret.isBlank()) {
            _toast.tryEmit(Toast(tr(R.string.login_empty_secret), true))
            return
        }
        runAction {
            _busy.value = true
            val result = api.login(server, secret)
            store.setServer(server.trim())
            store.setSecret(secret)
            store.setToken(result.token)
            _status.value = api.status()
            _authState.value = AuthState.SignedIn
            loadAll()
        }
    }

    fun logout() {
        runAction {
            _busy.value = true
            try {
                api.logout()
            } finally {
                api.token = ""
                editPasswordInMemory = null
                store.clearSession()
                _status.value = null
                _room.value = null
                _live.value = null
                _history.value = null
                _config.value = null
                _roles.value = emptyList()
                _authState.value = AuthState.SignedOut
                _toast.emit(Toast(tr(R.string.settings_logged_out), false))
            }
        }
    }

    /** 会话过期：清令牌回到登录页，保留服务器地址与密钥。 */
    fun forceRelogin() {
        viewModelScope.launch {
            api.token = ""
            editPasswordInMemory = null
            store.clearSession()
            _authState.value = AuthState.SignedOut
        }
    }

    // ------------------------------------------------------------ 读取

    fun loadAll() {
        loadStatus()
        loadRoom()
        loadLive()
        loadRoles()
    }

    fun loadStatus() = runAction { _status.value = api.status() }

    fun loadRoom() = runAction {
        val result = api.room()
        _roomError.value = result.error ?: result.warning
        if (result.error == null) _room.value = result.room
    }

    fun loadLive(user: String = "", channel: String = "") = runAction {
        _live.value = api.live(user, channel)
    }

    fun loadRoles() = runAction { _roles.value = api.roles().roles }

    /** 成员详情：读取该成员持有的身份组，同时刷新房间身份组表。 */
    fun loadRolesOf(userId: String) = runAction {
        val result = api.roles(userId)
        _roles.value = result.roles
        _userRoles.value = result.userRoles.toSet()
    }

    fun clearUserRoles() {
        _userRoles.value = emptySet()
    }

    private var historyUser = ""
    private var historyChannel = ""
    private var historyDate = ""
    private var historyArchived = true

    var historyLoaded: Boolean = false
        private set

    fun loadHistory(
        user: String = historyUser,
        channel: String = historyChannel,
        date: String = historyDate,
        archived: Boolean = historyArchived,
        offset: Int = 0,
    ) {
        historyUser = user
        historyChannel = channel
        historyDate = date
        historyArchived = archived
        runAction {
            val result = api.history(user, channel, date, offset = offset, archived = archived)
            _history.value = if (offset == 0) {
                result
            } else {
                result.copy(items = _history.value?.items.orEmpty() + result.items)
            }
            historyLoaded = true
        }
    }

    fun loadConfig() = runAction { _config.value = api.config().config }

    // ------------------------------------------------------------ 写入

    /** 发送成功后再关闭输入框：失败时保留草稿，避免长文本被一次网络错误吞掉。 */
    fun sendMessage(channelId: String, message: String, atUserId: String = "", onSent: () -> Unit = {}) = runAction {
        _busy.value = true
        api.sendMessage(channelId, message, atUserId)
        onSent()
        _toast.emit(Toast(tr(R.string.send_success), false))
    }

    /** enable=true 授予身份组，false 撤销；与记录编辑共用独立编辑密码。 */
    fun setRole(userId: String, role: RoleDto, enable: Boolean) = runAction {
        _busy.value = true
        api.updateRole(if (enable) "grant" else "revoke", userId, role.id, requireEditPassword())
        loadRolesOf(userId)
        _toast.emit(Toast(tr(if (enable) R.string.member_role_granted else R.string.member_role_revoked, role.name), false))
    }

    fun saveConfig(draft: ConfigDraft, original: PublicConfig, onSaved: () -> Unit = {}) = runAction {
        _busy.value = true
        val changes = buildConfigChanges(draft, original)
        if (changes.isEmpty()) {
            _toast.emit(Toast(tr(R.string.cfg_no_change), false))
            return@runAction
        }
        val result = api.saveConfig(changes, requireEditPassword())
        _config.value = result.config
        if (draft.newEditPassword.isNotBlank()) store.setEditPassword(draft.newEditPassword)
        onSaved()
        _toast.emit(Toast(configSavedText(result.applied, result.restartNeeded), false))
        loadStatus()
        loadRoom()
    }

    /** 只提交改动过的字段：掩码密钥与未填写的密码不参与写回。 */
    private fun buildConfigChanges(draft: ConfigDraft, original: PublicConfig): JsonObject = buildJsonObject {
        draft.pollInterval.toIntOrNull()?.takeIf { it != original.pollInterval }?.let { put("poll_interval", it) }
        if (draft.recordInitialOnline != original.recordInitialOnline) {
            put("record_initial_online", draft.recordInitialOnline)
        }
        if (draft.botEnabled != original.botEnabled) put("bot_enabled", draft.botEnabled)
        if (draft.channelIds != original.channelIds) put("channel_ids", stringArray(draft.channelIds))
        if (draft.admins != original.admins) put("admins", stringArray(draft.admins))
        if (draft.epicChannelId != original.epicPushChannelId) {
            put("epic_push_channel_id", draft.epicChannelId)
        }
        if (draft.epicTimes != original.epicPushTimes) put("epic_push_times", stringArray(draft.epicTimes))
        if (draft.heyboxId.isNotBlank() && draft.heyboxId != original.heyboxId) put("heybox_id", draft.heyboxId)
        if (draft.roomId.isNotBlank() && draft.roomId != original.roomId) put("room_id", draft.roomId)
        draft.webPort.toIntOrNull()?.takeIf { it != original.webPort }?.let { put("web_port", it) }
        if (draft.newToken.isNotBlank()) put("token", draft.newToken)
        if (draft.newEditPassword.isNotBlank()) put("edit_password", draft.newEditPassword)
    }

    private fun stringArray(values: List<String>): JsonElement = JsonArray(values.map { JsonPrimitive(it) })

    fun saveRecord(id: Int, joinTime: String, leaveTime: String, onSaved: () -> Unit = {}) = runAction {
        _busy.value = true
        api.editRecord(id, joinTime, leaveTime, requireEditPassword())
        onSaved()
        _toast.emit(Toast(tr(R.string.history_updated), false))
        loadHistory(offset = 0)
    }

    fun deleteRecord(id: Int) = runAction {
        _busy.value = true
        api.deleteRecord(id, requireEditPassword())
        _toast.emit(Toast(tr(R.string.history_deleted), false))
        loadHistory(offset = 0)
    }

    // ------------------------------------------------------------ 偏好设置

    fun setLanguage(tag: LanguageTag) = viewModelScope.launch { store.setLanguage(tag.tag) }

    fun setTheme(tag: ThemeTag) = viewModelScope.launch { store.setTheme(tag.tag) }

    /**
     * 编辑密码：本机缓存供后续管理操作复用，避免每次写操作都弹窗。
     * 内存字段保证刚输入的密码在下一次写操作中立即可用（DataStore 写入是异步的）。
     */
    @Volatile
    private var editPasswordInMemory: String? = null

    fun currentEditPassword(): String =
        editPasswordInMemory?.takeIf { it.isNotBlank() } ?: _settings.value.editPassword

    fun hasEditPassword(): Boolean = currentEditPassword().isNotBlank()

    fun provideEditPassword(value: String) {
        editPasswordInMemory = value
        viewModelScope.launch { store.setEditPassword(value) }
    }

    // ------------------------------------------------------------ 工具

    private fun requireEditPassword(): String =
        currentEditPassword().ifBlank { throw ApiException(403, tr(R.string.edit_password_required)) }

    private fun runAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (exc: CancellationException) {
                throw exc
            } catch (exc: Exception) {
                if (exc is ApiException && exc.status == 401) forceRelogin()
                _toast.emit(Toast(readableError(exc), true))
            } finally {
                _busy.value = false
            }
        }
    }

    private fun configSavedText(applied: List<String>, restartNeeded: List<String>): String = when {
        applied.isEmpty() -> tr(R.string.cfg_no_change)
        restartNeeded.isEmpty() -> tr(R.string.cfg_saved)
        else -> tr(R.string.cfg_restart_needed, restartNeeded.joinToString("、"))
    }

    private fun readableError(exc: Throwable): String = when (exc) {
        is ApiException -> exc.message ?: tr(R.string.error_generic)
        is NetworkException -> exc.message ?: tr(R.string.error_network)
        else -> exc.message ?: tr(R.string.error_generic)
    }

    private fun tr(resId: Int, vararg args: Any?): String =
        if (args.isEmpty()) getApplication<Application>().getString(resId)
        else getApplication<Application>().getString(resId, *args)
}
