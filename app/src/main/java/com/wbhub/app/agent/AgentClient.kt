package com.wbhub.app.agent

import android.content.Context
import com.wbhub.app.bridge.CallLogStore
import com.wbhub.app.bridge.CallRecord
import com.wbhub.app.proto.CredentialStore
import com.wbhub.app.proto.Region
import com.wbhub.app.proto.UpstreamClient
import com.wbhub.app.proto.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap

/**
 * 内置对话客户端：直接使用当前账号的模型聊天，并支持工具调用（读文件、写文件、bash）。
 *
 * 单轮流程：请求 → 流式解析 SSE → 若模型返回 tool_calls 则本地执行工具 → 把结果回灌 → 继续下一轮，
 * 直到模型给出最终文本回复或达到最大轮数。
 */
class AgentClient(
    context: Context,
    private val credentials: CredentialStore,
    private val upstream: UpstreamClient,
    private val tools: AgentTools = AgentTools(context),
    private val callLog: CallLogStore = CallLogStore(context.applicationContext),
) {

    data class ToolCall(val id: String, val name: String, val arguments: JSONObject)

    private class ToolCallBuilder {
        var id: String = ""
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    private data class StreamResult(val content: String, val toolCalls: List<ToolCall>)

    /** 会话 id 在进程内固定，上游据此归并用量；一次发送内所有轮次复用同一个请求 id。 */
    private val conversationId: String by lazy { digest("wbhub|${java.util.UUID.randomUUID()}") }
    private var requestSeq = 0

    suspend fun ask(
        region: Region,
        model: String,
        prompt: String,
        onEvent: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        if (!credentials.isLoggedIn(region)) {
            throw IllegalStateException("${region.displayName}尚未登录，请先在凭证页完成浏览器登录")
        }
        val messages = JSONArray()
            .put(systemMessage())
            .put(JSONObject().put("role", "user").put("content", prompt))

        val requestId = digest("$conversationId|${++requestSeq}|$prompt")
        var round = 0
        while (round++ < MAX_ROUNDS) {
            val request = JSONObject()
                .put("model", model)
                .put("stream", true)
                .put("messages", messages)
                .put("tools", tools.openAiSchemas())

            val result = streamOnce(region, request, round, requestId, messages.length(), onEvent)
            if (result.toolCalls.isEmpty()) return@withContext result.content

            messages.put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", result.content)
                    .put("tool_calls", toolCallsJson(result.toolCalls)),
            )
            for (call in result.toolCalls) {
                onEvent("[tool] ${call.name} ${call.arguments}")
                val output = tools.execute(call.name, call.arguments)
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", call.id)
                        .put("content", output),
                )
            }
        }
        return@withContext "已达到最大工具调用轮数（$MAX_ROUNDS）"
    }

    private fun streamOnce(
        region: Region,
        request: JSONObject,
        round: Int,
        requestId: String,
        messageCount: Int,
        onEvent: (String) -> Unit,
    ): StreamResult {
        val payload = request.toString().toByteArray(Charsets.UTF_8)
        val startedAt = System.currentTimeMillis()
        val content = StringBuilder()
        val builders: MutableMap<Int, ToolCallBuilder> = TreeMap()
        val pending = StringBuilder()
        val errorBody = StringBuilder()
        val rawTail = StringBuilder()
        var errorStatus = 0
        var retryAfter = ""

        upstream.streamChat(
            region = region,
            body = payload,
            onStatus = { code, headers ->
                if (code !in 200..299) {
                    errorStatus = code
                    retryAfter = headers.entries
                        .firstOrNull { it.key?.equals("Retry-After", ignoreCase = true) == true }
                        ?.value?.firstOrNull().orEmpty()
                }
            },
            onChunk = { bytes, length ->
                val text = String(bytes, 0, length, Charsets.UTF_8)
                // 原文留一份进调用日志：能直接看出哪句是上游 content、哪句是本地 [tool] 标记
                if (rawTail.length < CallLogStore.RAW_LIMIT) rawTail.append(text)
                // 非 2xx 时把响应体当纯文本收，用于报错原文
                if (errorStatus != 0) {
                    if (errorBody.length < ERROR_BODY_LIMIT) errorBody.append(text)
                    return@streamChat
                }
                pending.append(text)
                var index = pending.indexOf("\n")
                while (index >= 0) {
                    val line = pending.substring(0, index).trim()
                    pending.delete(0, index + 1)
                    consumeSseLine(line, content, builders, onEvent)
                    index = pending.indexOf("\n")
                }
            },
            extraHeaders = Wire.conversationHeaders(
                conversationId = conversationId,
                conversationRequestId = requestId,
                conversationMessageId = digest("$requestId|$round"),
                requestId = digest("$requestId|$round|$messageCount"),
            ),
        )
        if (pending.isNotEmpty()) consumeSseLine(pending.toString().trim(), content, builders, onEvent)

        val calls = builders.values.map { builder ->
            ToolCall(
                id = builder.id,
                name = builder.name.toString(),
                arguments = runCatching { JSONObject(builder.arguments.toString()) }.getOrElse { JSONObject() },
            )
        }
        val (promptTokens, completionTokens) = parseUsage(rawTail.toString())
        callLog.append(
            CallRecord(
                time = startedAt,
                kind = CallLogStore.KIND_CHAT,
                region = region.id,
                model = request.optString("model", "unknown"),
                status = if (errorStatus != 0) errorStatus else 200,
                durationMs = System.currentTimeMillis() - startedAt,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                credits = null,
                note = if (calls.isEmpty()) null else "工具调用 ${calls.size} 个（第 ${round + 1} 轮）",
                raw = rawTail.toString().take(CallLogStore.RAW_LIMIT),
            ),
        )

        if (errorStatus != 0 && content.isEmpty() && builders.isEmpty()) {
            throw IllegalStateException(describeError(errorStatus, retryAfter, errorBody.toString()))
        }
        return StreamResult(content.toString(), calls)
    }

    /** 从 SSE 原文里取最后一处 `usage`，上游只在末帧给。 */
    private fun parseUsage(raw: String): Pair<Int, Int> {
        var prompt = 0
        var completion = 0
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) return@forEach
            val frame = runCatching { JSONObject(trimmed.removePrefix("data:").trim()) }.getOrNull()
                ?: return@forEach
            val usage = frame.optJSONObject("usage") ?: return@forEach
            prompt = usage.optInt("prompt_tokens", prompt)
            completion = usage.optInt("completion_tokens", completion)
        }
        return prompt to completion
    }

    /**
     * 把上游状态码翻成能直接照做的提示。
     *
     * 429 是上游限流，免费额度模型（如 hy3）通常有并发数与频次上限，换模型或等一会儿都能缓解；
     * 具体原因以响应体原文为准，所以这里原样带上（截断，避免刷屏）。
     */
    private fun describeError(code: Int, retryAfter: String, body: String): String {
        val detail = body.replace(Regex("\\s+"), " ").trim().take(ERROR_BODY_LIMIT)
        val suffix = if (detail.isEmpty()) "" else "；上游原文：$detail"
        return when (code) {
            429 -> "上游限流（HTTP 429）" +
                (if (retryAfter.isNotBlank()) "，建议 $retryAfter 秒后再试" else "，请等几秒再试或换一个模型") +
                suffix
            401, 403 -> "上游拒绝访问（HTTP $code），令牌可能已失效，请到凭证页刷新 Token$suffix"
            else -> "上游返回 HTTP $code$suffix"
        }
    }

    /** 定长 id，取 SHA-256 前 32 位十六进制。 */
    private fun digest(input: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)

    private fun consumeSseLine(
        line: String,
        content: StringBuilder,
        builders: MutableMap<Int, ToolCallBuilder>,
        onEvent: (String) -> Unit,
    ) {
        if (line.isEmpty() || !line.startsWith("data:")) return
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty() || data == "[DONE]") return
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return
        val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return
        delta.optString("content").takeIf { it.isNotEmpty() }?.let {
            content.append(it)
            onEvent(it)
        }
        val toolCalls = delta.optJSONArray("tool_calls") ?: return
        for (i in 0 until toolCalls.length()) {
            val item = toolCalls.optJSONObject(i) ?: continue
            val index = item.optInt("index", i)
            val builder = builders.getOrPut(index) { ToolCallBuilder() }
            item.optString("id").takeIf { it.isNotEmpty() }?.let { builder.id = it }
            item.optJSONObject("function")?.let { function ->
                builder.name.append(function.optString("name"))
                builder.arguments.append(function.optString("arguments"))
            }
        }
    }

    private fun systemMessage(): JSONObject = JSONObject()
        .put("role", "system")
        .put(
            "content",
            "你是运行在 Android 设备上的助手，可以调用工具读写工作区文件、执行 shell 命令。" +
                "需要实际操作时请调用工具，不要凭空猜测结果。",
        )

    private fun toolCallsJson(calls: List<ToolCall>): JSONArray {
        val array = JSONArray()
        calls.forEach { call ->
            array.put(
                JSONObject()
                    .put("id", call.id)
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject().put("name", call.name).put("arguments", call.arguments.toString()),
                    ),
            )
        }
        return array
    }

    private companion object {
        const val MAX_ROUNDS = 6
        /** 报错原文最多保留的字符数，够看出上游给的原因又不至于刷屏。 */
        const val ERROR_BODY_LIMIT = 300
    }
}
