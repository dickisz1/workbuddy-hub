package com.wbhub.app.proto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

data class HttpResponse(val code: Int, val body: String)

/**
 * 上游调用客户端：只负责与 WorkBuddy 私有接口通信。
 *
 * - [streamChat] 以原始字节块回调，保证 SSE 流式输出不被二次编码破坏；
 * - [jsonRequest] 处理普通一次性请求（模型列表、签到等）；
 * - 每次调用前补齐全套身份头，并在 accessToken 临近过期时用 refreshToken 静默续期。
 */
class UpstreamClient(private val credentials: CredentialStore) {

    private val refreshing = AtomicBoolean(false)

    /** 流式对话：状态码与响应头回吐一次，随后按块透传字节。 */
    fun streamChat(
        region: Region,
        body: ByteArray,
        onStatus: (Int, Map<String, List<String>>) -> Unit,
        onChunk: (ByteArray, Int) -> Unit,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val conn = open(region, Wire.UPSTREAM_CHAT_PATH, "POST", body, extraHeaders)
        conn.setRequestProperty("Accept", "text/event-stream")
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            onStatus(code, conn.headerFields ?: emptyMap())
            val stream: InputStream? =
                if (code in 200..299) conn.inputStream else conn.errorStream
            if (stream == null) {
                onChunk(ByteArray(0), 0)
                return
            }
            BufferedInputStream(stream).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    onChunk(buffer.copyOf(read), read)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 一次性请求（阻塞版）。 */
    fun jsonRequest(region: Region, path: String, method: String, body: String?): HttpResponse {
        val payload = body?.toByteArray(Charsets.UTF_8)
        val conn = open(region, path, method, payload)
        try {
            if (payload != null) {
                conn.outputStream.use { it.write(payload) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { readAll(it) } ?: ""
            return HttpResponse(code, text)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 模型清单取自 `GET /v3/config`：
     * `data.agents` 里 `cli` 一项的 `models` 是可用模型 id，`data.models` 是模型详情。
     */
    suspend fun fetchModels(region: Region): List<String> = withContext(Dispatchers.IO) {
        val response = jsonRequest(region, Wire.UPSTREAM_MODELS_PATH, "GET", null)
        if (response.code !in 200..299) return@withContext emptyList()
        runCatching {
            val root = JSONObject(response.body)
            val data = root.optJSONObject("data") ?: root
            val detailed = data.optJSONArray("models") ?: JSONArray()
            val enabled = enabledModels(data.optJSONArray("agents"))
            val source = if (enabled.length() > 0) enabled else detailed
            buildList {
                for (i in 0 until source.length()) {
                    val item = source.optJSONObject(i)
                    val id = if (item != null) {
                        item.optString("id").ifBlank { item.optString("name") }
                    } else {
                        source.optString(i)
                    }
                    if (id.isNotBlank() && id != "null") add(id)
                }
            }.distinct()
        }.getOrDefault(emptyList())
    }

    /** `data.agents[name=cli].models` 里的条目可能是字符串或对象。 */
    private fun enabledModels(agents: JSONArray?): JSONArray {
        if (agents == null) return JSONArray()
        for (i in 0 until agents.length()) {
            val agent = agents.optJSONObject(i) ?: continue
            if (agent.optString("name") != "cli") continue
            return agent.optJSONArray("models") ?: JSONArray()
        }
        return JSONArray()
    }

    private fun open(
        region: Region,
        path: String,
        method: String,
        body: ByteArray?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpURLConnection {
        val conn = URL(Wire.upstreamUrl(region, path)).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = 0 // SSE 允许长连接，0 表示不超时
        conn.doInput = true
        if (body != null) conn.doOutput = true
        conn.setRequestProperty("User-Agent", Wire.UPSTREAM_USER_AGENT)
        if (body != null) conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        applyIdentity(conn, region)
        extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }

    /**
     * 补齐 WorkBuddy 原生身份头：`Authorization: Bearer` + uid / domain / 客户端标识 / 企业头。
     */
    private fun applyIdentity(conn: HttpURLConnection, region: Region) {
        val account = credentials.account(region) ?: return
        val token = tokenWithRefresh(region, account)
        conn.setRequestProperty("Authorization", "Bearer $token")
        Wire.identityHeaders(
            userId = account.uid,
            domain = account.domain,
            region = region,
            enterpriseId = account.enterpriseId,
        ).forEach { (k, v) -> conn.setRequestProperty(k, v) }
    }

    /**
     * 取可用 accessToken：临近过期时用 refreshToken 换新并回写 [CredentialStore]。
     * 用一个原子标记防止刷新请求自身再次触发刷新；刷新失败时退回旧 token。
     */
    private fun tokenWithRefresh(region: Region, account: Account): String {
        if (!credentials.needsRefresh(region)) return account.accessToken
        if (account.refreshToken.isBlank()) return account.accessToken
        if (!refreshing.compareAndSet(false, true)) return account.accessToken
        return try {
            val result = AuthApi.refresh(
                region = region,
                refreshToken = account.refreshToken,
                domain = account.domain,
                enterpriseId = account.enterpriseId,
            )
            val access = result.accessToken ?: account.accessToken
            if (result.accessToken != null) {
                credentials.updateTokens(
                    region = region,
                    accessToken = access,
                    refreshToken = result.refreshToken.orEmpty().ifBlank { account.refreshToken },
                    expiresAt = result.expiresAt,
                    refreshExpiresAt = result.refreshExpiresAt,
                )
            }
            access
        } finally {
            refreshing.set(false)
        }
    }

    private fun readAll(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toString("UTF-8")
    }

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
        const val CONNECT_TIMEOUT_MS = 15_000
    }
}
