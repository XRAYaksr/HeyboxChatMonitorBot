@file:OptIn(ExperimentalSerializationApi::class)

package com.heychat.monitor.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 后端 JSON 接口的数据模型。字段名通过 SnakeCase 命名策略映射，
 * 与 main.py 中 build_status / dashboard / query_history / public_config 的返回保持一致。
 */
@Serializable
data class StatusDto(
    val running: Boolean = false,
    val uptimeSeconds: Long = 0,
    val uptimeText: String = "",
    val startedAt: String? = null,
    val lastUpdate: String? = null,
    val pollInterval: Int = 0,
    val pollCount: Int = 0,
    val channels: List<String> = emptyList(),
    val channelErrors: Map<String, String> = emptyMap(),
    val onlineCount: Int = 0,
    val botEnabled: Boolean = false,
    val botConnected: Boolean = false,
    val editEnabled: Boolean = false,
    val error: String? = null,
)

@Serializable
data class LoginDto(
    val ok: Boolean = false,
    val token: String = "",
    val ttlSeconds: Long = 0,
    val error: String? = null,
)

@Serializable
data class SessionDto(
    val ok: Boolean = false,
    val status: StatusDto = StatusDto(),
    val error: String? = null,
)

@Serializable
data class OkDto(
    val ok: Boolean = false,
    val error: String? = null,
)

@Serializable
data class RoomDto(
    val ok: Boolean = false,
    val room: RoomInfo = RoomInfo(),
    val warning: String? = null,
    val error: String? = null,
)

@Serializable
data class RoomInfo(
    val roomId: String = "",
    val roomName: String = "",
    val roomAvatar: String = "",
    val roomIntro: String = "",
    val channels: List<ChannelInfo> = emptyList(),
)

@Serializable
data class ChannelInfo(
    val channelId: String = "",
    val channelName: String = "",
    val channelType: Int? = null,
    val apiType: String = "",
    val isVoice: Boolean = false,
    val monitored: Boolean = false,
)

@Serializable
data class RolesDto(
    val ok: Boolean = false,
    val roles: List<RoleDto> = emptyList(),
    val userRoles: List<String> = emptyList(),
    val warning: String? = null,
    val error: String? = null,
)

@Serializable
data class RoleDto(
    val id: String = "",
    val name: String = "",
    val color: String = "",
)

@Serializable
data class LiveDataDto(
    val online: List<OnlineUserDto> = emptyList(),
    val recentOnline: List<RecentOnlineDto> = emptyList(),
    val lastUpdate: String = "-",
    val errors: Map<String, String> = emptyMap(),
    val channels: List<String> = emptyList(),
    val error: String? = null,
)

@Serializable
data class OnlineUserDto(
    val userId: String = "",
    val username: String = "",
    val avatar: String = "",
    val channelId: String = "",
    val joinTime: String? = null,
    val currentSeconds: Long? = null,
)

@Serializable
data class RecentOnlineDto(
    val userId: String = "",
    val username: String = "",
    val avatar: String = "",
    val channelId: String = "",
    val leaveTime: String = "",
    val ago: String = "",
)

@Serializable
data class HistoryDto(
    val ok: Boolean = false,
    val items: List<HistoryItemDto> = emptyList(),
    val total: Int = 0,
    val limit: Int = 100,
    val offset: Int = 0,
    val summary: List<UserSummaryDto> = emptyList(),
    val error: String? = null,
)

@Serializable
data class HistoryItemDto(
    val id: Int = 0,
    val userId: String = "",
    val channelId: String = "",
    val joinTime: String? = null,
    val leaveTime: String? = null,
    val durationSeconds: Long? = null,
    val duration: String = "",
    val archived: Boolean = false,
    @SerialName("open") val isOpen: Boolean = false,
    val username: String = "",
    val avatar: String = "",
)

@Serializable
data class UserSummaryDto(
    val userId: String = "",
    val sessions: Int = 0,
    val totalSeconds: Long = 0,
    val totalText: String = "",
    val username: String = "",
    val avatar: String = "",
)

@Serializable
data class ConfigDto(
    val ok: Boolean = false,
    val config: PublicConfig = PublicConfig(),
    val error: String? = null,
)

@Serializable
data class ConfigWriteDto(
    val ok: Boolean = false,
    val applied: List<String> = emptyList(),
    val restartNeeded: List<String> = emptyList(),
    val config: PublicConfig = PublicConfig(),
    val error: String? = null,
)

/** 与 main.py 的 public_config() 对应：密钥只以掩码下发，密码只暴露是否已设置。 */
@Serializable
data class PublicConfig(
    val tokenMasked: String = "",
    val hasToken: Boolean = false,
    val heyboxId: String = "",
    val roomId: String = "",
    val channelIds: List<String> = emptyList(),
    val pollInterval: Int = 5,
    val recordInitialOnline: Boolean = false,
    val webPort: Int = 8000,
    val botEnabled: Boolean = true,
    val admins: List<String> = emptyList(),
    val epicPushChannelId: String = "",
    val epicPushTimes: List<String> = listOf("12:00"),
    val hasWebPassword: Boolean = false,
    val hasEditPassword: Boolean = false,
)
