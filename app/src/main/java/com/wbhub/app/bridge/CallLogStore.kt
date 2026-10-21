package com.wbhub.app.bridge

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CallRecord(
    val time: Long,
    /** 来源：`api` 为本地 `/v1` 转发，`chat` 为内置对话。 */
    val kind: String,
    val region: String,
    val model: String,
    val status: Int,
    val durationMs: Long,
    val promptTokens: Int,
    val completionTokens: Int,
    val credits: String?,
    val note: String?,
    /** 上游响应原文（SSE 帧或错误体），超长时截断。 */
    val raw: String?,
)

/**
 * 调用日志：按 JSONL 追加写入私有目录。
 *
 * 每条记录包含模型、状态码、耗时、Token 用量、积分消耗，以及**上游响应原文**——
 * 排查「是谁返回的这行字」时不必再猜：`data:` 帧是上游，`[tool]` 之类是本地生成。
 */
class CallLogStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private val lock = Any()

    fun append(record: CallRecord) {
        val json = JSONObject().apply {
            put("time", record.time)
            put("kind", record.kind)
            put("region", record.region)
            put("model", record.model)
            put("status", record.status)
            put("duration_ms", record.durationMs)
            put("prompt_tokens", record.promptTokens)
            put("completion_tokens", record.completionTokens)
            put("credits", record.credits ?: "")
            put("note", record.note ?: "")
            put("raw", record.raw ?: "")
        }
        synchronized(lock) {
            file.appendText(json.toString() + "\n")
            trimIfNeeded()
        }
    }

    /** 最近 [limit] 条（新的在前）。 */
    fun recent(limit: Int = 50): List<CallRecord> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        file.readLines().takeLast(limit).reversed().mapNotNull { parse(it) }
    }

    fun clear() {
        synchronized(lock) { if (file.exists()) file.delete() }
    }

    /**
     * 供界面展示的文本。
     *
     * 每条先给一行摘要（时间、来源、模型、状态、耗时、用量），下面再附上游原文的单行预览，
     * 便于直接判断「这句话是上游说的，还是本地拼的」。
     */
    fun formatted(limit: Int = 20): String = recent(limit).joinToString("\n") { record ->
        val stamp = timeFormat.format(Date(record.time))
        val kind = when (record.kind) {
            KIND_CHAT -> "对话"
            KIND_SERVICE -> "服务"
            else -> "转发"
        }
        val credits = record.credits?.takeIf { it.isNotBlank() }?.let { " 积分=$it" }.orEmpty()
        val note = record.note?.takeIf { it.isNotBlank() }?.let { "  $it" }.orEmpty()
        val head = "$stamp [$kind] ${record.model} ${record.status} ${record.durationMs}ms " +
            "in=${record.promptTokens} out=${record.completionTokens}$credits$note"
        val raw = record.raw?.takeIf { it.isNotBlank() }?.let {
            "\n  ↳ " + it.replace(Regex("\\s+"), " ").trim().take(RAW_PREVIEW)
        }.orEmpty()
        head + raw
    }

    private fun parse(line: String): CallRecord? = runCatching {
        val json = JSONObject(line)
        CallRecord(
            time = json.optLong("time"),
            kind = json.optString("kind").ifBlank { KIND_API },
            region = json.optString("region"),
            model = json.optString("model"),
            status = json.optInt("status"),
            durationMs = json.optLong("duration_ms"),
            promptTokens = json.optInt("prompt_tokens"),
            completionTokens = json.optInt("completion_tokens"),
            credits = json.optString("credits").takeIf { it.isNotBlank() },
            note = json.optString("note").takeIf { it.isNotBlank() },
            raw = json.optString("raw").takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    private fun trimIfNeeded() {
        val lines = file.readLines()
        if (lines.size <= MAX_LINES) return
        file.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
    }

    companion object {
        const val KIND_API = "api"
        const val KIND_CHAT = "chat"
        const val KIND_SERVICE = "svc"

        /** 单条日志里保留的上游原文上限，够看清是帧还是错误体。 */
        const val RAW_LIMIT = 2000

        private const val FILE_NAME = "call_log.jsonl"
        private const val MAX_LINES = 2000
        private const val RAW_PREVIEW = 180
    }
}
