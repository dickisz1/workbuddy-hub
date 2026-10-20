package com.wbhub.app.proto

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * OAuth2 `client_credentials` 令牌端点，供企业凭证换取 access_token。
 *
 * 官方 IAM 文档给出的就是这一条：拿企业应用控制台发放的 client_id + client_secret，
 * POST 到 `https://<host>/oauth2/token`。个人账号不走这里，他们用静态 API Key。
 *
 * 实测（假凭证）：返回 `{"error":"invalid_client",...}`，说明端点为真、只认真凭证。
 */
object TokenEndpoint {

    data class Result(val accessToken: String?, val expiresInSec: Long, val error: String?)

    fun request(region: Region, clientId: String, clientSecret: String): Result {
        val form = listOf(
            "grant_type" to "client_credentials",
            "client_id" to clientId,
            "client_secret" to clientSecret,
            "scope" to "*",
        ).joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

        val conn = URL(region.tokenUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Accept", "application/json")
        conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }

        val code = runCatching { conn.responseCode }.getOrDefault(-1)
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText) ?: ""
        conn.disconnect()

        if (code !in 200..299) {
            val detail = runCatching { JSONObject(body).optString("error_description") }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(160)
            return Result(null, 0L, "HTTP $code ${detail.trim()}")
        }
        return runCatching {
            val access = JSONObject(body).optString("access_token")
            if (access.isBlank()) Result(null, 0L, "响应缺少 access_token")
            else Result(access, JSONObject(body).optLong("expires_in", 3600L), null)
        }.getOrElse { Result(null, 0L, it.message ?: "解析响应失败") }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000
}
