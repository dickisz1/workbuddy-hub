package com.wbhub.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.wbhub.app.proto.Account
import com.wbhub.app.proto.CredentialStore
import com.wbhub.app.proto.Region
import com.wbhub.app.proto.Wire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * OAuth 授权流程（与官方 CLI 同款 PKCE）。
 *
 * 1. 生成 code_verifier / code_challenge；
 * 2. 打开系统浏览器进入授权页；
 * 3. 本地 loopback ServerSocket 等待回调（自定义 scheme 由 OAuthRedirectActivity 兜底）；
 * 4. 用 code 换取 access_token + refresh_token，写进 [CredentialStore]。
 */
class Login(private val context: Context, private val credentials: CredentialStore) {

    private data class Session(
        val region: Region,
        val verifier: String,
        val state: String,
        val redirectUri: String,
        val deferred: CompletableDeferred<Account>,
    )

    @Volatile
    private var session: Session? = null

    /** 阻塞直到拿到凭证、超时或出错。调用方负责放到 IO 协程里执行。 */
    suspend fun login(region: Region, callbackPort: Int = DEFAULT_CALLBACK_PORT): Account {
        val verifier = randomString(64)
        val state = randomString(24)
        val redirectUri = "${Wire.REDIRECT_SCHEME}:$callbackPort/oauth/callback"
        val deferred = CompletableDeferred<Account>()
        val current = Session(region, verifier, state, redirectUri, deferred)
        session = current

        val listener = LoopbackCallbackListener(callbackPort) { query ->
            handleCallback(current, query)
        }
        listener.start()
        try {
            openBrowser(authorizeUrl(region, redirectUri, challenge(verifier), state))
            return withTimeout(OAUTH_TIMEOUT_MS) { deferred.await() }
        } finally {
            listener.stop()
            session = null
        }
    }

    /** 自定义 scheme 回落路径（OAuthRedirectActivity 调用）。 */
    fun handleRedirect(uri: Uri) {
        val current = session ?: return
        val query = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }
        handleCallback(current, query)
    }

    private fun handleCallback(current: Session, query: Map<String, String>) {
        val error = query["error"]
        if (!error.isNullOrBlank()) {
            current.deferred.completeExceptionally(IllegalStateException("OAuth 失败: $error"))
            return
        }
        if (query["state"] != current.state) {
            current.deferred.completeExceptionally(IllegalStateException("state 校验失败"))
            return
        }
        val code = query["code"]
        if (code.isNullOrBlank()) {
            current.deferred.completeExceptionally(IllegalStateException("回调缺少 code"))
            return
        }
        runCatching { exchangeCode(current, code) }
            .onSuccess { current.deferred.complete(it) }
            .onFailure { current.deferred.completeExceptionally(it) }
    }

    private fun exchangeCode(current: Session, code: String): Account {
        val form = listOf(
            "grant_type" to "authorization_code",
            "client_id" to current.region.clientId,
            "code" to code,
            "code_verifier" to current.verifier,
            "redirect_uri" to current.redirectUri,
        ).joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

        val conn = URL(current.region.tokenUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Accept", "application/json")
        conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }

        val status = conn.responseCode
        val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText) ?: ""
        conn.disconnect()
        if (status !in 200..299) throw IllegalStateException("换取 Token 失败($status): $body")

        val json = JSONObject(body)
        val access = json.optString("access_token")
        if (access.isBlank()) throw IllegalStateException("响应缺少 access_token")
        val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() }
        val expiresIn = json.optLong("expires_in", 3600L)
        credentials.saveAccount(current.region, access, refresh, expiresIn)
        return Account(current.region, access, refresh, System.currentTimeMillis() + expiresIn * 1000L)
    }

    private fun authorizeUrl(region: Region, redirectUri: String, challenge: String, state: String): String =
        buildString {
            append(region.authorizeUrl)
            append("?response_type=code")
            append("&client_id=").append(enc(region.clientId))
            append("&redirect_uri=").append(enc(redirectUri))
            append("&scope=").append(enc("openid profile offline_access"))
            append("&code_challenge=").append(challenge)
            append("&code_challenge_method=S256")
            append("&state=").append(state)
        }

    private fun openBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun randomString(length: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val random = SecureRandom()
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val DEFAULT_CALLBACK_PORT = 53947
        const val OAUTH_TIMEOUT_MS = 5 * 60 * 1000L
    }
}

/**
 * 只接受一次回调的 loopback 监听器：浏览器重定向到 127.0.0.1:port 时读取 query。
 */
private class LoopbackCallbackListener(
    private val port: Int,
    private val onQuery: (Map<String, String>) -> Unit,
) {

    private var server: ServerSocket? = null
    private var worker: Thread? = null

    fun start() {
        server = ServerSocket(port, 1, InetAddress.getByName(Wire.LOOPBACK))
        worker = thread(name = "oauth-loopback") { serve() }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun serve() {
        val socket = runCatching { server?.accept() }.getOrNull() ?: return
        try {
            val requestLine = BufferedReader(InputStreamReader(socket.getInputStream())).readLine() ?: ""
            respond(socket.getOutputStream())
            val path = requestLine.split(" ").getOrNull(1) ?: ""
            onQuery(parseQuery(path))
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun respond(out: OutputStream) {
        val body = "<html><body><h3>授权完成，可以关闭本页面。</h3></body></html>"
        val bytes = body.toByteArray(Charsets.UTF_8)
        out.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.UTF_8),
        )
        out.write(bytes)
        out.flush()
    }

    private fun parseQuery(path: String): Map<String, String> {
        val raw = path.substringAfter('?', "")
        if (raw.isEmpty()) return emptyMap()
        return raw.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null else URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }.toMap()
    }
}
