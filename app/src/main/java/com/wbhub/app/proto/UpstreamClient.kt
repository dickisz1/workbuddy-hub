package com.wbhub.app.proto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * - 每次调用前经 [applyAuth] 按登录方式补鉴权头（API Key 或 Bearer Token）。
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

    suspend fun fetchModels(region: Region): List<String> = withContext(Dispatchers.IO) {
        val response = jsonRequest(region, Wire.UPSTREAM_MODELS_PATH, "GET", null)
        if (response.code !in 200..299) return@withContext emptyList()
        runCatching {
            val json = JSONObject(response.body)
            val array = json.optJSONArray("data") ?: json.optJSONArray("models") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i)
                    val id = item?.optString("id") ?: array.optString(i)
                    if (!id.isNullOrBlank()) add(id)
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 按当前登录方式补鉴权头，两种模式互斥：
     * - 个人账号：静态 API Key → `X-Api-Key`；
     * - 企业账号：access_token 临近过期先用 Client 凭证换新 → `Authorization: Bearer`。
     */
    private fun applyAuth(conn: HttpURLConnection, region: Region) {
        when (credentials.mode(region)) {
            AuthMode.API_KEY ->
                credentials.apiKey(region)?.let { conn.setRequestProperty(Wire.HEADER_API_KEY, it) }

            AuthMode.CLIENT_CREDENTIALS -> {
                val token = tokenWithRefresh(region) ?: return
                conn.setRequestProperty("Authorization", "Bearer $token")
            }

            AuthMode.NONE -> Unit
        }
    }

    /**
     * 企业模式取 token：临近过期先用 Client 凭证换新并回写 [CredentialStore]。
     * 用一个原子标记防止换新请求自身再次触发换新。
     */
    private fun tokenWithRefresh(region: Region): String? {
        val token = credentials.accessToken(region) ?: return null
        if (!credentials.needsRefresh(region)) return token
        if (!refreshing.compareAndSet(false, true)) return token
        return try {
            refresh(region) ?: token
        } finally {
            refreshing.set(false)
        }
    }

    /**
     * 企业账号：用已存的 Client ID / Secret 走 client_credentials 换新 access_token。
     * 个人 API Key 模式没有 Client 凭证，直接返回 null。
     */
    fun refresh(region: Region): String? {
        val id = credentials.clientId(region) ?: return null
        val secret = credentials.clientSecret(region) ?: return null
        val result = TokenEndpoint.request(region, id, secret)
        val token = result.accessToken ?: return null
        credentials.updateAccessToken(region, token, result.expiresInSec)
        return token
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
        // uid 目前还没有从登录态解析出来，暂以设备指纹占位；接入真实登录后应换成账号 uid
        Wire.identityHeaders(credentials.deviceId(), region).forEach { (k, v) ->
            conn.setRequestProperty(k, v)
        }
        extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        applyAuth(conn, region)
        return conn
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
