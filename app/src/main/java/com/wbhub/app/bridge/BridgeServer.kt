package com.wbhub.app.bridge

import com.wbhub.app.proto.CredentialStore
import com.wbhub.app.proto.Region
import com.wbhub.app.proto.UpstreamClient
import com.wbhub.app.proto.Wire
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 最小可用的 OpenAI 兼容反向代理。
 *
 * 手写 [ServerSocket] + 线程池，请求/响应全部按**原始字节**处理，
 * 避免 SSE 流式输出被二次编码破坏（不乱码、不中断）。
 *
 * 处理链路：校验共享密钥 → 解析并改写请求体 → 按登录方式补身份头与鉴权头
 * → 转发到上游 → 原样透传 SSE 字节流。
 */
class BridgeServer(
    private val port: Int,
    private val secret: String,
    private val credentials: CredentialStore,
    private val upstream: UpstreamClient,
    private val callLog: CallLogStore,
) {

    private data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private val pool = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)

    /** 上游调用序号，只用于给请求级的 id 提供区分度。 */
    private val upstreamCallSeq = AtomicLong(0)
    private var serverSocket: ServerSocket? = null

    /** 事件回调，用于把监听状态写到界面上。 */
    @Volatile
    var onEvent: ((String) -> Unit)? = null

    /** 阻塞式启动：绑定 loopback 并开始 accept。 */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = ServerSocket(port, BACKLOG, InetAddress.getByName(Wire.LOOPBACK))
        serverSocket = socket
        thread(name = "bridge-accept") { acceptLoop(socket) }
        emit("已监听 ${Wire.LOOPBACK}:$port")
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        pool.shutdownNow()
        emit("转发服务已停止")
    }

    val isRunning: Boolean get() = running.get()

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            runCatching { pool.execute { handle(client) } }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = socket.getInputStream()
            val out = socket.getOutputStream()
            val request = runCatching { readRequest(input) }.getOrNull()
            if (request == null) {
                sendError(out, 400, "无法解析请求")
                return
            }
            try {
                dispatch(request, out)
            } catch (io: Exception) {
                // 客户端中途断开是常态（SSE），不视为错误。
                emit("客户端断开: ${io.message}")
            }
        }
    }

    private fun dispatch(request: Request, out: OutputStream) {
        if (request.method == "OPTIONS") {
            sendCors(out, 204)
            return
        }
        // ① 校验共享密钥
        val provided = Wire.extractToken(request.headers["authorization"])
        if (provided.isNullOrBlank() || provided != secret) {
            sendError(out, 401, "共享密钥无效，请在 Authorization: Bearer <密钥> 中携带")
            return
        }
        when (request.path) {
            Wire.CHAT_COMPLETIONS_PATH -> handleChat(request, out)
            Wire.MODELS_PATH -> handleModels(request, out)
            Wire.HEALTH_PATH -> sendJson(out, 200, JSONObject().put("status", "ok"))
            else -> sendError(out, 404, "未知路径 ${request.path}")
        }
    }

    /** 一次转发的统计结果，用于写调用记录。 */
    private data class ForwardResult(
        val status: Int,
        val promptTokens: Int,
        val completionTokens: Int,
        val credits: String?,
        /** 上游响应原文（SSE 帧 / 错误体），按 [CallLogStore.RAW_LIMIT] 截断。 */
        val raw: String,
        val note: String? = null,
    )

    private fun handleChat(request: Request, out: OutputStream) {
        val started = System.currentTimeMillis()
        // 悬浮窗要显示的「谁在调用」：本地只能拿到 UA，拿不到对方包名
        BridgeStats.begin(BridgeStats.describeClient(request.headers["user-agent"]))
        // ② 解析请求体（按字节读入，UTF-8 解码仅用于结构改写）
        val rewritten = rewriteChatBody(request.body)
        if (rewritten == null) {
            logFailure(request, 400, "请求体不是合法的 JSON", started)
            sendError(out, 400, "请求体不是合法的 JSON")
            return
        }
        val (json, payload, wantsStream) = rewritten

        val region = resolveRegion(request)
        if (!credentials.isLoggedIn(region)) {
            val message = "该区域（${region.displayName}）尚未登录，请先在凭证页完成浏览器登录"
            logFailure(request, 401, message, started, region.id, json.optString("model", "unknown"))
            sendError(out, 401, message)
            return
        }

        val model = json.optString("model", "unknown")
        val sessionHeaders = deriveConversationHeaders(request, json)

        // ③④⑤ 转发：补身份头与 access_token 由 UpstreamClient 统一完成
        val result = try {
            if (wantsStream) {
                forwardStreaming(region, payload, out, sessionHeaders, model)
            } else {
                forwardBuffered(region, payload, out, sessionHeaders, model)
            }
        } catch (e: Exception) {
            // 客户端断开、上游挂断等：仍要留痕，否则日志里看不到这次调用
            logFailure(request, 0, e.message ?: e.javaClass.simpleName, started, region.id, model)
            return
        }

        callLog.append(
            CallRecord(
                time = started,
                kind = CallLogStore.KIND_API,
                region = region.id,
                model = model,
                status = result.status,
                durationMs = System.currentTimeMillis() - started,
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                credits = result.credits,
                note = result.note,
                raw = result.raw.take(CallLogStore.RAW_LIMIT),
            ),
        )
        val elapsed = System.currentTimeMillis() - started
        BridgeStats.finish(model, result.status, elapsed, result.note)
        emit("转发 $model → ${result.status} (${elapsed}ms)")
    }

    /** 请求在到达上游前就失败（或中途异常）时也写一条记录，便于核对。 */
    private fun logFailure(
        request: Request,
        status: Int,
        note: String,
        started: Long,
        region: String = resolveRegion(request).id,
        model: String = "-",
    ) {
        val elapsed = System.currentTimeMillis() - started
        callLog.append(
            CallRecord(
                time = started,
                kind = CallLogStore.KIND_API,
                region = region,
                model = model,
                status = status,
                durationMs = elapsed,
                promptTokens = 0,
                completionTokens = 0,
                credits = null,
                note = note,
                raw = null,
            ),
        )
        BridgeStats.finish(model, status, elapsed, note)
    }

    /** `stream:true`：上游 SSE 字节原样透传。 */
    private fun forwardStreaming(
        region: Region,
        payload: ByteArray,
        out: OutputStream,
        sessionHeaders: Map<String, String>,
        model: String,
    ): ForwardResult {
        var status = 0
        val usageTail = StringBuilder()
        val rawTail = StringBuilder()
        val credits = arrayOfNulls<String>(1)

        upstream.streamChat(
            region = region,
            body = payload,
            onStatus = { code, headers ->
                status = code
                credits[0] = creditsOf(headers)
                sendStreamHead(out, code, credits[0])
            },
            onChunk = { bytes, length ->
                out.write(bytes, 0, length)
                out.flush()
                // 原文单独留一份写进调用日志，方便逐帧核对
                if (rawTail.length < CallLogStore.RAW_LIMIT) {
                    rawTail.append(String(bytes, 0, length, Charsets.UTF_8))
                }
                // 只保留尾部少量文本用于事后统计 usage
                if (usageTail.length < USAGE_TAIL_LIMIT) {
                    usageTail.append(String(bytes, 0, length, Charsets.UTF_8))
                }
            },
            extraHeaders = sessionHeaders,
        )

        val (promptTokens, completionTokens) = parseUsage(usageTail.toString())
        val note = when {
            status == 0 -> "上游无响应"
            status !in 200..299 -> "上游拒绝"
            else -> null
        }
        return ForwardResult(status, promptTokens, completionTokens, credits[0], rawTail.toString(), note)
    }

    /**
     * `stream:false`：上游仍按 SSE 拉取，本地聚合成单条 `chat.completion` 再返回。
     *
     * 上游只支持流式；若不聚合，非流式客户端会把 `:heartbeat` / `data:` 当成 JSON 解析并报
     * 「Expected literal value at character 0」。
     */
    private fun forwardBuffered(
        region: Region,
        payload: ByteArray,
        out: OutputStream,
        sessionHeaders: Map<String, String>,
        model: String,
    ): ForwardResult {
        var status = 0
        val buffer = ByteArrayOutputStream()
        val credits = arrayOfNulls<String>(1)

        upstream.streamChat(
            region = region,
            body = payload,
            onStatus = { code, headers ->
                status = code
                credits[0] = creditsOf(headers)
            },
            onChunk = { bytes, length -> buffer.write(bytes, 0, length) },
            extraHeaders = sessionHeaders,
        )

        val text = buffer.toString("UTF-8")
        if (status !in 200..299) {
            // 上游错误体本身是 JSON，原样回传，避免包装后丢掉上游错误码
            sendRawJson(out, status, text, credits[0])
            val note = if (status == 0) "上游无响应" else "上游拒绝"
            return ForwardResult(status, 0, 0, credits[0], text, note)
        }

        val completion = aggregateCompletion(text, model)
        sendJson(out, 200, completion, credits[0])
        val usage = completion.optJSONObject("usage")
        return ForwardResult(
            status = 200,
            promptTokens = usage?.optInt("prompt_tokens") ?: 0,
            completionTokens = usage?.optInt("completion_tokens") ?: 0,
            credits = credits[0],
            raw = text,
        )
    }

    private fun creditsOf(headers: Map<String, List<String>>): String? =
        headers.entries
            .firstOrNull { it.key.equals(HEADER_CREDITS, ignoreCase = true) }
            ?.value?.firstOrNull()

    private fun handleModels(request: Request, out: OutputStream) {
        val region = resolveRegion(request)
        if (!credentials.isLoggedIn(region)) {
            sendError(out, 401, "该区域（${region.displayName}）尚未登录")
            return
        }
        val models = runCatching { runBlocking { upstream.fetchModels(region) } }.getOrDefault(emptyList())
        val data = JSONArray()
        models.forEach { id ->
            data.put(JSONObject().put("id", id).put("object", "model").put("owned_by", Wire.PRODUCT_NAME))
        }
        sendJson(
            out,
            200,
            JSONObject().put("object", "list").put("data", data),
        )
    }

    // ------------------------------------------------------------------
    // 请求体改写
    // ------------------------------------------------------------------

    /**
     * ③ 改写请求体：
     * - 记住客户端是否要流式：上游只支持流式，所以 `stream` 恒置 true，响应形态由调用方按
     *   [Triple.third] 决定（非流式在本地聚合成单条 `chat.completion`）；
     * - 把上游不认识的 `developer` 角色降级为 `system`；
     * - 移除不兼容的可选字段。
     */
    private fun rewriteChatBody(body: ByteArray): Triple<JSONObject, ByteArray, Boolean>? {
        val json = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull() ?: return null

        val wantsStream = json.optBoolean("stream", false)
        json.put("stream", true)

        val messages = json.optJSONArray("messages")
        if (messages != null) {
            val kept = JSONArray()
            for (i in 0 until messages.length()) {
                val message = messages.optJSONObject(i) ?: continue
                when (message.optString("role")) {
                    ROLE_DEVELOPER -> if (message.has("content")) {
                        kept.put(JSONObject(message.toString()).put("role", ROLE_SYSTEM))
                    }
                    else -> kept.put(message)
                }
            }
            json.put("messages", kept)
        }
        INCOMPATIBLE_FIELDS.forEach { json.remove(it) }

        return Triple(json, json.toString().toByteArray(Charsets.UTF_8), wantsStream)
    }

    /**
     * 派生请求级的会话 id 头。
     *
     * 上游按 `X-Conversation-Request-ID` 把「一次用户发送」算作一个请求：同一次发送的工具
     * 结果回灌、重试都要复用同一个值，用户下一条消息才换新的。
     *
     * 本地是 OpenAI 兼容接口，没有会话概念，所以取值规则属于**推断**（不是上游规定的算法）：
     * - 会话：客户端显式带的 `X-Conversation-ID` 优先，否则取首条 user 消息；
     * - 一次发送：由「user 消息条数 + 最后一条 user 消息」决定，同一发送的后续步骤因此不变；
     * - 消息 / 请求：按本次上游调用的序号区分。
     *
     * 会话头和 user 消息都没有时不编造——上游文档说编造的会话会把一次对话在用量明细里拆开。
     */
    private fun deriveConversationHeaders(request: Request, json: JSONObject): Map<String, String> {
        val messages = json.optJSONArray("messages") ?: return emptyMap()
        var firstUser: String? = null
        var lastUser: String? = null
        var userCount = 0
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            if (message.optString("role") != ROLE_USER) continue
            val content = message.optString("content").takeIf { it.isNotEmpty() }
            if (firstUser == null) firstUser = content
            lastUser = content
            userCount++
        }
        if (userCount == 0) return emptyMap()

        val seed = request.headers[Wire.HEADER_CONVERSATION_ID.lowercase()] ?: firstUser ?: return emptyMap()
        val conversationId = digestId(seed)
        val conversationRequestId = digestId("$conversationId|$userCount|$lastUser")
        val conversationMessageId = digestId("$conversationRequestId|${messages.length()}")
        val requestId = digestId("$conversationMessageId|${upstreamCallSeq.incrementAndGet()}")
        return Wire.conversationHeaders(conversationId, conversationRequestId, conversationMessageId, requestId)
    }

    /** 取 SHA-256 前 [ID_LENGTH] 位十六进制，得到定长、可复现的 id。 */
    private fun digestId(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(ID_LENGTH)

    // ------------------------------------------------------------------
    // SSE 聚合（供非流式客户端）
    // ------------------------------------------------------------------

    /**
     * 把上游的 SSE 增量拼成一条完整的 `chat.completion`。
     *
     * 上游帧形如 `data: {...}`，另有 `:heartbeat` 注释行与结尾的 `data: [DONE]`，都要跳过。
     * `tool_calls` 按 `index` 归并：`id` / `name` 取首个非空值，`arguments` 逐帧拼接。
     */
    private fun aggregateCompletion(sse: String, fallbackModel: String): JSONObject {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = sortedMapOf<Int, JSONObject>()
        var id = ""
        var model = fallbackModel
        var created = 0L
        var finishReason = ""
        var usage: JSONObject? = null

        sse.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith(SSE_DATA_PREFIX)) return@forEach
            val data = line.removePrefix(SSE_DATA_PREFIX).trim()
            if (data.isEmpty() || data == SSE_DONE) return@forEach
            val chunk = runCatching { JSONObject(data) }.getOrNull() ?: return@forEach

            if (id.isEmpty()) id = chunk.text("id")
            chunk.text("model").takeIf { it.isNotEmpty() }?.let { model = it }
            if (created == 0L) created = chunk.optLong("created", 0L)
            chunk.optJSONObject("usage")?.let { usage = it }

            val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: return@forEach
            choice.text("finish_reason").takeIf { it.isNotEmpty() }?.let { finishReason = it }
            val delta = choice.optJSONObject("delta") ?: return@forEach
            content.append(delta.text("content"))
            reasoning.append(delta.text("reasoning_content"))

            val calls = delta.optJSONArray("tool_calls") ?: return@forEach
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val index = call.optInt("index", i)
                val merged = toolCalls.getOrPut(index) {
                    JSONObject()
                        .put("index", index)
                        .put("type", "function")
                        .put("function", JSONObject().put("name", "").put("arguments", ""))
                }
                call.text("id").takeIf { it.isNotEmpty() }?.let { merged.put("id", it) }
                val function = call.optJSONObject("function") ?: continue
                val target = merged.getJSONObject("function")
                function.text("name").takeIf { it.isNotEmpty() }?.let { target.put("name", it) }
                function.text("arguments").takeIf { it.isNotEmpty() }?.let {
                    target.put("arguments", target.optString("arguments") + it)
                }
            }
        }

        val message = JSONObject().put("role", ROLE_ASSISTANT).put("content", content.toString())
        if (reasoning.isNotEmpty()) message.put("reasoning_content", reasoning.toString())
        if (toolCalls.isNotEmpty()) {
            // 个别客户端要求 tool_calls[].id 非空，缺失时补一个本地 id
            toolCalls.forEach { (index, call) ->
                if (call.text("id").isEmpty()) call.put("id", "call_local_$index")
            }
            message.put("tool_calls", JSONArray(toolCalls.values.toList()))
        }

        val normalizedUsage = usage ?: JSONObject()
            .put("prompt_tokens", 0)
            .put("completion_tokens", 0)
            .put("total_tokens", 0)

        return JSONObject()
            .put("id", id.ifEmpty { DEFAULT_COMPLETION_ID })
            .put("object", "chat.completion")
            .put("created", created)
            .put("model", model)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("message", message)
                        .put("finish_reason", finishReason.ifEmpty { FINISH_STOP }),
                ),
            )
            .put("usage", normalizedUsage)
    }

    /** 取字符串字段；显式 `null` 与缺失都返回空串，避免拼出字面量 `null`。 */
    private fun JSONObject.text(name: String): String {
        val value = opt(name) ?: return ""
        if (value === JSONObject.NULL) return ""
        val text = value.toString()
        return if (text == "null") "" else text
    }

    // ------------------------------------------------------------------
    // 响应写出
    // ------------------------------------------------------------------

    private fun sendStreamHead(out: OutputStream, code: Int, credits: String?) {
        val head = buildString {
            append("HTTP/1.1 $code ${reason(code)}\r\n")
            append("Content-Type: text/event-stream; charset=utf-8\r\n")
            append("Cache-Control: no-cache\r\n")
            append("X-Accel-Buffering: no\r\n")
            if (!credits.isNullOrBlank()) append("$HEADER_CREDITS: $credits\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun sendJson(
        out: OutputStream,
        code: Int,
        payload: JSONObject,
        credits: String? = null,
    ) {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code ${reason(code)}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            if (!credits.isNullOrBlank()) append("$HEADER_CREDITS: $credits\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    /** 上游错误体原样回传；不是 JSON 时包成 `{"error":{...}}`，保证客户端能解析。 */
    private fun sendRawJson(out: OutputStream, code: Int, body: String, credits: String?) {
        val text = body.trim()
        val payload = runCatching { JSONObject(text) }.getOrNull()
            ?: JSONObject().put(
                "error",
                JSONObject()
                    .put("message", text.take(MAX_ERROR_CHARS).ifBlank { "上游返回空响应" })
                    .put("type", "upstream_error")
                    .put("code", code),
            )
        sendJson(out, code, payload, credits)
    }

    private fun sendError(out: OutputStream, code: Int, message: String) {
        val payload = JSONObject().put(
            "error",
            JSONObject().put("message", message).put("type", "bridge_error").put("code", code),
        )
        runCatching { sendJson(out, code, payload) }
    }

    private fun sendCors(out: OutputStream, code: Int) {
        runCatching {
            out.write(
                (
                    "HTTP/1.1 $code No Content\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Headers: Authorization, Content-Type\r\n" +
                        "Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
            )
            out.flush()
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 区域选择：请求头 / query 指定，缺省用已登录且优先国内版的账号。 */
    private fun resolveRegion(request: Request): Region {
        request.headers[HEADER_REGION]?.let { return Region.fromId(it) }
        request.query[HEADER_REGION]?.let { return Region.fromId(it) }
        return when {
            credentials.isLoggedIn(Region.CN) -> Region.CN
            credentials.isLoggedIn(Region.INTL) -> Region.INTL
            else -> Region.CN
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0)?.uppercase() ?: return null
        val target = parts.getOrNull(1) ?: return null

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }

        val body = when {
            // 两者同时出现时以 chunked 为准（RFC 7230 §3.3.3）
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true ->
                readChunked(input)

            else -> {
                val length = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                if (length > 0) readFully(input, length) else ByteArray(0)
            }
        }

        val path = target.substringBefore('?')
        val rawQuery = target.substringAfter('?', "")
        val query = rawQuery.split("&")
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
            }
            .toMap()

        return Request(method, path, query, headers, body)
    }

    /** 逐字节读一行，保证不会把二进制请求体误当成文本处理。 */
    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (buffer.size() == 0) null else buffer.toString(CHARSET_LATIN1)
            if (byte == NEWLINE) return buffer.toString(CHARSET_LATIN1).removeSuffix("\r")
            buffer.write(byte)
        }
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(out, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset == length) out else out.copyOf(offset)
    }

    /**
     * 读取 chunked 请求体。
     *
     * 每块格式为 `<十六进制长度>[;扩展]\r\n<数据>\r\n`，长度为 0 表示结束，
     * 其后可跟可选的 trailer 头，需一并丢弃，否则残留字节会污染下一次读取。
     */
    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            // `;` 之后的 chunk 扩展一律忽略
            val size = sizeLine.trim().substringBefore(';').trim().toIntOrNull(CHUNK_RADIX) ?: break
            if (size <= 0) {
                discardTrailers(input)
                break
            }
            out.write(readFully(input, size))
            readLine(input) // 块数据之后的 CRLF
        }
        return out.toByteArray()
    }

    /** 丢弃 chunked 结束后的 trailer 段（以空行收尾）。 */
    private fun discardTrailers(input: InputStream) {
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
        }
    }

    /** 从 SSE 尾部文本里提取 usage（上游在最后一帧返回）。 */
    private fun parseUsage(tail: String): Pair<Int, Int> {
        val marker = tail.lastIndexOf("\"usage\"")
        if (marker < 0) return 0 to 0
        val start = tail.indexOf('{', marker)
        if (start < 0) return 0 to 0
        val end = tail.indexOf('}', start)
        if (end < 0) return 0 to 0
        return runCatching {
            val usage = JSONObject(tail.substring(start, end + 1))
            usage.optInt("prompt_tokens") to usage.optInt("completion_tokens")
        }.getOrDefault(0 to 0)
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "Upstream Response"
    }

    private fun emit(message: String) {
        onEvent?.invoke(message)
    }

    private companion object {
        const val BACKLOG = 50
        const val SOCKET_TIMEOUT_MS = 120_000
        const val NEWLINE = '\n'.code
        const val CHARSET_LATIN1 = "ISO-8859-1"
        const val CHUNK_RADIX = 16
        const val HEADER_CREDITS = "X-Credits-Consumed"
        const val HEADER_REGION = "x-wb-region"
        const val ROLE_DEVELOPER = "developer"
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val SSE_DATA_PREFIX = "data:"
        const val SSE_DONE = "[DONE]"
        const val DEFAULT_COMPLETION_ID = "chatcmpl-local"
        const val FINISH_STOP = "stop"
        const val MAX_ERROR_CHARS = 500
        const val ID_LENGTH = 16
        const val USAGE_TAIL_LIMIT = 8 * 1024
        val INCOMPATIBLE_FIELDS = listOf("stream_options", "logprobs", "top_logprobs")
    }
}
