package com.wbhub.app.agent

import android.content.Context
import com.wbhub.app.proto.CredentialStore
import com.wbhub.app.proto.Region
import com.wbhub.app.proto.UpstreamClient
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
) {

    data class ToolCall(val id: String, val name: String, val arguments: JSONObject)

    private class ToolCallBuilder {
        var id: String = ""
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    private data class StreamResult(val content: String, val toolCalls: List<ToolCall>)

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

        var round = 0
        while (round++ < MAX_ROUNDS) {
            val request = JSONObject()
                .put("model", model)
                .put("stream", true)
                .put("messages", messages)
                .put("tools", tools.openAiSchemas())

            val result = streamOnce(region, request, onEvent)
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

    private fun streamOnce(region: Region, request: JSONObject, onEvent: (String) -> Unit): StreamResult {
        val payload = request.toString().toByteArray(Charsets.UTF_8)
        val content = StringBuilder()
        val builders: MutableMap<Int, ToolCallBuilder> = TreeMap()
        val pending = StringBuilder()
        var error: String? = null

        upstream.streamChat(
            region = region,
            body = payload,
            onStatus = { code, _ -> if (code !in 200..299) error = "上游返回 HTTP $code" },
            onChunk = { bytes, length ->
                pending.append(String(bytes, 0, length, Charsets.UTF_8))
                var index = pending.indexOf("\n")
                while (index >= 0) {
                    val line = pending.substring(0, index).trim()
                    pending.delete(0, index + 1)
                    consumeSseLine(line, content, builders, onEvent)
                    index = pending.indexOf("\n")
                }
            },
        )
        if (pending.isNotEmpty()) consumeSseLine(pending.toString().trim(), content, builders, onEvent)
        error?.let { if (content.isEmpty() && builders.isEmpty()) throw IllegalStateException(it) }

        val calls = builders.values.map { builder ->
            ToolCall(
                id = builder.id,
                name = builder.name.toString(),
                arguments = runCatching { JSONObject(builder.arguments.toString()) }.getOrElse { JSONObject() },
            )
        }
        return StreamResult(content.toString(), calls)
    }

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
    }
}
