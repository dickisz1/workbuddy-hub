package com.wbhub.app.proto

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * WorkBuddy 原生登录接口。
 *
 * 完整流程（与官方客户端一致）：
 * 1. [createState] 申请 `state`，拿到登录页 `authUrl`；
 * 2. 用户在浏览器完成登录，客户端轮询 [pollToken] 取 `accessToken` / `refreshToken`；
 * 3. 用 accessToken 调 [whoAmI] 取 `uid` / `enterpriseId`；
 * 4. accessToken 临近过期时用 [refresh] 静默续期。
 *
 * 所有响应都是 `{code, msg, data}` 信封；登录未完成时 `code` 为
 * [Wire.CODE_SIGN_IN_PENDING]，需要继续轮询。
 */
object AuthApi {

    data class SignInState(val state: String?, val authUrl: String?, val error: String?)

    data class Token(
        val accessToken: String?,
        val refreshToken: String?,
        val expiresAt: Long,
        val refreshExpiresAt: Long,
        val domain: String?,
        /** 用户还没完成浏览器登录，继续轮询即可。 */
        val pending: Boolean,
        val error: String?,
    )

    data class Who(
        val uid: String?,
        val enterpriseId: String?,
        val nickname: String?,
        val pending: Boolean,
        val error: String?,
    )

    /** 第一步：申请登录 state 与登录页地址。 */
    fun createState(region: Region): SignInState {
        val url = Wire.upstreamUrl(region, Wire.AUTH_STATE_PATH) + "?platform=" + region.platform
        val (code, body) = call(url, "POST", noAuthHeaders() + jsonContentType, PAYLOAD_EMPTY)
        val root = root(body)
        if (code !in 200..299 || root == null) {
            return SignInState(null, null, describe(code, body, root))
        }
        val biz = root.optInt("code", 0)
        if (biz != 0) {
            return SignInState(null, null, describe(code, body, root))
        }
        val data = root.optJSONObject("data") ?: root
        val state = data.optString("state").takeIf { it.isNotBlank() }
        val authUrl = data.optString("authUrl").takeIf { it.isNotBlank() }
        if (state == null || authUrl == null) {
            return SignInState(null, null, "响应缺少 state / authUrl")
        }
        return SignInState(state, authUrl, null)
    }

    /** 第二步：轮询登录结果。返回 [Token.pending] 为 true 时继续等。 */
    fun pollToken(region: Region, state: String): Token {
        val url = Wire.upstreamUrl(region, Wire.AUTH_TOKEN_PATH) + "?state=" + encode(state)
        val (code, body) = call(url, "GET", noAuthHeaders(), null)
        return parseToken(code, body, Wire.CODE_SIGN_IN_PENDING)
    }

    /** 第三步：用 accessToken + 同一个 state 取账号信息。 */
    fun whoAmI(region: Region, state: String, accessToken: String, domain: String): Who {
        val url = Wire.upstreamUrl(region, Wire.LOGIN_ACCOUNT_PATH) + "?state=" + encode(state)
        val headers = linkedMapOf(
            "Authorization" to "Bearer $accessToken",
            Wire.HEADER_DOMAIN to domain.ifBlank { region.apiDomain },
            Wire.HEADER_NO_USER_ID to "true",
            Wire.HEADER_NO_ENTERPRISE_ID to "true",
            "User-Agent" to Wire.UPSTREAM_USER_AGENT,
        )
        val (code, body) = call(url, "GET", headers, null)
        val root = root(body)
        if (code !in 200..299 || root == null) {
            return Who(null, null, null, false, describe(code, body, root))
        }
        val biz = root.optInt("code", 0)
        if (biz == Wire.CODE_ACCOUNT_PENDING) {
            return Who(null, null, null, true, null)
        }
        if (biz != 0) {
            return Who(null, null, null, false, describe(code, body, root))
        }
        val data = root.optJSONObject("data") ?: root
        val uid = data.optString("uid").takeIf { it.isNotBlank() }
            ?: data.optString("userId").takeIf { it.isNotBlank() }
        val enterpriseId = data.optString("enterpriseId").takeIf { it.isNotBlank() }
            ?: data.optJSONObject("enterprise")?.optString("id")?.takeIf { it.isNotBlank() }
            ?: ""
        if (uid == null) {
            return Who(null, null, null, false, "账号信息缺少 uid")
        }
        return Who(uid, enterpriseId, data.optString("nickname").orEmpty(), false, null)
    }

    /** 第四步：用 refreshToken 换新令牌。 */
    fun refresh(region: Region, refreshToken: String, domain: String, enterpriseId: String): Token {
        val headers = linkedMapOf(
            Wire.HEADER_REFRESH_TOKEN to refreshToken,
            Wire.HEADER_AUTH_REFRESH_SOURCE to Wire.AUTH_REFRESH_SOURCE_VALUE,
            Wire.HEADER_DOMAIN to domain.ifBlank { region.apiDomain },
            "User-Agent" to Wire.UPSTREAM_USER_AGENT,
        )
        headers.putAll(jsonContentType)
        if (enterpriseId.isNotBlank()) {
            headers[Wire.HEADER_ENTERPRISE_ID] = enterpriseId
            headers[Wire.HEADER_TENANT_ID] = enterpriseId
        }
        val url = Wire.upstreamUrl(region, Wire.AUTH_REFRESH_PATH)
        val (code, body) = call(url, "POST", headers, PAYLOAD_EMPTY)
        return parseToken(code, body, -1)
    }

    private fun parseToken(httpCode: Int, body: String, pendingCode: Int): Token {
        val root = root(body)
        if (httpCode !in 200..299 || root == null) {
            return Token(null, null, 0, 0, null, false, describe(httpCode, body, root))
        }
        val biz = root.optInt("code", 0)
        if (pendingCode > 0 && biz == pendingCode) {
            return Token(null, null, 0, 0, null, true, null)
        }
        if (biz != 0) {
            return Token(null, null, 0, 0, null, false, describe(httpCode, body, root))
        }
        val data = root.optJSONObject("data") ?: root
        val access = data.optString("accessToken").takeIf { it.isNotBlank() }
            ?: data.optString("access_token").takeIf { it.isNotBlank() }
            ?: return Token(null, null, 0, 0, null, false, "响应缺少 accessToken")
        val refresh = data.optString("refreshToken").takeIf { it.isNotBlank() }
            ?: data.optString("refresh_token").orEmpty()
        val now = System.currentTimeMillis()
        val expiresAt = absoluteTime(
            data.optLong("expiresAt", 0L),
            data.optLong("expiresOn", 0L),
            data.optLong("expiresIn", 0L),
            now,
        )
        val refreshExpiresAt = absoluteTime(
            data.optLong("refreshExpiresAt", 0L),
            data.optLong("refreshExpiresOn", 0L),
            data.optLong("refreshExpiresIn", 0L),
            now,
        )
        val domain = data.optString("domain").takeIf { it.isNotBlank() }
        return Token(access, refresh, expiresAt, refreshExpiresAt, domain, false, null)
    }

    /**
     * 上游同时存在「毫秒时间戳」和「相对秒数」两种过期字段，统一换算成毫秒时间戳。
     */
    private fun absoluteTime(absolute: Long, absoluteAlt: Long, relativeSeconds: Long, now: Long): Long {
        val value = if (absolute > 0) absolute else absoluteAlt
        if (value > 0) return if (value < 1_000_000_000_000L) value * 1000 else value
        if (relativeSeconds > 0) return now + relativeSeconds * 1000
        return 0L
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun noAuthHeaders(): MutableMap<String, String> = linkedMapOf(
        Wire.HEADER_NO_AUTHORIZATION to "true",
        Wire.HEADER_NO_USER_ID to "true",
        Wire.HEADER_NO_ENTERPRISE_ID to "true",
        Wire.HEADER_NO_DEPARTMENT_INFO to "true",
        "User-Agent" to Wire.UPSTREAM_USER_AGENT,
    )

    private fun root(body: String): JSONObject? =
        runCatching { JSONObject(body) }.getOrNull()

    private fun describe(httpCode: Int, body: String, root: JSONObject?): String {
        val message = root?.optString("msg").orEmpty().ifBlank { root?.optString("message").orEmpty() }
        if (message.isNotBlank()) return "HTTP $httpCode $message"
        return "HTTP $httpCode ${body.take(160).ifBlank { "空响应" }}"
    }

    private fun call(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
    ): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doInput = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = runCatching { conn.responseCode }.getOrDefault(-1)
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            return code to text
        } catch (e: Exception) {
            return -1 to (e.message ?: "网络错误")
        } finally {
            conn.disconnect()
        }
    }

    private val jsonContentType = mapOf("Content-Type" to "application/json; charset=utf-8")

    private const val PAYLOAD_EMPTY = "{}"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
}
