package com.heychat.monitor.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/** 服务端返回的业务错误（含 HTTP 非 2xx）。message 可直接展示给用户。 */
class ApiException(val status: Int, message: String) : Exception(message)

/** 网络层错误：地址不可达、超时、DNS 失败等。 */
class NetworkException(message: String) : Exception(message)

@OptIn(ExperimentalSerializationApi::class)
val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    namingStrategy = JsonNamingStrategy.SnakeCase
}

/**
 * 后端 JSON 接口客户端。鉴权分两层：会话令牌（X-Session-Token 头）覆盖读取与发频道消息；
 * 改身份组、改配置、改/删记录额外需要独立的编辑密码。
 */
class ApiClient {

    @Volatile var baseUrl: String = ""
    @Volatile var token: String = ""

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 20000

        /** 接受 `192.168.1.5`、`192.168.1.5:8000`、`http://host:8000/` 几种写法。 */
        fun normalizeBase(raw: String): String? {
            var text = raw.trim()
            if (text.isEmpty() || text.any { it.isWhitespace() }) return null
            if (!text.contains("://")) text = "http://$text"
            return try {
                val host = URL(text).host
                if (host.isNullOrBlank()) null else text.trimEnd('/')
            } catch (exc: MalformedURLException) {
                null
            }
        }
    }

    // ---------------------------------------------------------------- 底层请求

    private suspend fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: String? = null,
    ): String = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) throw NetworkException("尚未配置服务器地址")
        val url = buildString {
            append(baseUrl)
            append(path)
            if (query.isNotEmpty()) {
                append('?')
                append(query.entries.joinToString("&") { (key, value) ->
                    encode(key) + "=" + encode(value)
                })
            }
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            if (token.isNotEmpty()) setRequestProperty("X-Session-Token", token)
        }
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (status !in 200..299) {
                throw ApiException(status, serverMessage(text) ?: defaultError(status))
            }
            text
        } catch (exc: SocketTimeoutException) {
            throw NetworkException("连接超时，请检查服务器地址与网络")
        } catch (exc: IOException) {
            throw NetworkException("无法连接服务器：${exc.message ?: "网络不可达"}")
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun serverMessage(text: String): String? = try {
        ApiJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    } catch (exc: Exception) {
        null
    }

    private fun defaultError(status: Int): String = when (status) {
        401 -> "会话已失效，请重新登录"
        403 -> "编辑密码不正确"
        503 -> "机器人未启动"
        else -> "服务器返回错误（HTTP $status）"
    }

    private suspend inline fun <reified T : Any> get(
        path: String,
        query: Map<String, String> = emptyMap(),
    ): T = ApiJson.decodeFromString(request("GET", path, query))

    private suspend inline fun <reified T : Any> post(path: String, payload: JsonElement): T =
        ApiJson.decodeFromString(
            request("POST", path, body = ApiJson.encodeToString(JsonElement.serializer(), payload))
        )

    // ---------------------------------------------------------------- 会话接口

    /** 登录成功才切换地址与令牌，失败时保留原有连接信息。 */
    suspend fun login(server: String, password: String): LoginDto {
        val base = normalizeBase(server) ?: throw ApiException(0, "服务器地址格式不正确")
        val previousBase = baseUrl
        val previousToken = token
        baseUrl = base
        token = ""
        return try {
            val result = post<LoginDto>("/api/login", buildJsonObject { put("password", password) })
            if (result.token.isBlank()) {
                baseUrl = previousBase
                token = previousToken
                throw ApiException(500, "服务器未返回会话令牌")
            }
            token = result.token
            result
        } catch (exc: Exception) {
            baseUrl = previousBase
            token = previousToken
            throw exc
        }
    }

    suspend fun logout(): OkDto = post("/api/logout", buildJsonObject { })

    suspend fun session(): SessionDto = get("/api/session")

    suspend fun status(): StatusDto = get("/api/status")

    // ---------------------------------------------------------------- 数据接口

    suspend fun room(): RoomDto = get("/api/room")

    suspend fun live(user: String = "", channel: String = ""): LiveDataDto =
        get("/api/data", queryOf("user" to user, "channel" to channel))

    suspend fun roles(userId: String? = null): RolesDto =
        get("/api/roles", queryOf("user_id" to userId.orEmpty()))

    suspend fun history(
        user: String = "",
        channel: String = "",
        date: String = "",
        limit: Int = 100,
        offset: Int = 0,
        archived: Boolean = true,
    ): HistoryDto = get(
        "/api/history",
        queryOf("user" to user, "channel" to channel, "date" to date) + mapOf(
            "limit" to limit.toString(),
            "offset" to offset.toString(),
            "archived" to if (archived) "1" else "0",
        ),
    )

    // ---------------------------------------------------------------- 管理接口

    suspend fun sendMessage(channelId: String, message: String, atUserId: String = ""): OkDto =
        post(
            "/api/send",
            buildJsonObject {
                put("channel_id", channelId)
                put("msg", message)
                if (atUserId.isNotBlank()) put("at_user_id", atUserId)
            },
        )

    /** action 取 grant 或 revoke。 */
    suspend fun updateRole(action: String, userId: String, roleId: String, password: String): OkDto =
        post(
            "/api/role",
            buildJsonObject {
                put("action", action)
                put("user_id", userId)
                put("role_id", roleId)
                put("password", password)
            },
        )

    suspend fun config(): ConfigDto = get("/api/config")

    suspend fun saveConfig(changes: JsonObject, password: String): ConfigWriteDto =
        post(
            "/api/config",
            buildJsonObject {
                put("password", password)
                put("config", changes)
            },
        )

    suspend fun editRecord(id: Int, joinTime: String, leaveTime: String, password: String): OkDto =
        post(
            "/api/edit",
            buildJsonObject {
                put("id", id)
                put("join_time", joinTime)
                put("leave_time", leaveTime)
                put("password", password)
            },
        )

    suspend fun deleteRecord(id: Int, password: String): OkDto =
        post(
            "/api/delete",
            buildJsonObject {
                put("id", id)
                put("password", password)
            },
        )
}

/** 只保留非空值的查询参数。 */
fun queryOf(vararg pairs: Pair<String, String>): Map<String, String> =
    pairs.filter { it.second.isNotBlank() }.toMap()
