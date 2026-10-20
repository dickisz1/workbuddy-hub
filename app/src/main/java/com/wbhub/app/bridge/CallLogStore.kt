package com.wbhub.app.bridge

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CallRecord(
    val time: Long,
    val region: String,
    val model: String,
    val status: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val credits: String?,
    val note: String?,
)

/**
 * 调用日志：按 JSONL 追加写入私有目录，记录模型、Token 用量与积分消耗。
 */
class CallLogStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private val lock = Any()

    fun append(record: CallRecord) {
        val json = JSONObject().apply {
            put("time", record.time)
            put("region", record.region)
            put("model", record.model)
            put("status", record.status)
            put("prompt_tokens", record.promptTokens)
            put("completion_tokens", record.completionTokens)
            put("credits", record.credits ?: "")
            put("note", record.note ?: "")
        }
        synchronized(lock) {
            file.appendText(json.toString() + "\n")
            trimIfNeeded()
        }
    }

    /** 最近 [limit] 条（新的在前）。 */
    fun recent(limit: Int = 50): List<CallRecord> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        val lines = file.readLines()
        lines.takeLast(limit).reversed().mapNotNull { parse(it) }
    }

    fun clear() {
        synchronized(lock) { if (file.exists()) file.delete() }
    }

    fun formatted(limit: Int = 20): String = recent(limit).joinToString("\n") { record ->
        val stamp = timeFormat.format(Date(record.time))
        val credits = record.credits?.takeIf { it.isNotBlank() }?.let { " 积分=$it" } ?: ""
        "$stamp  ${record.model}  ${record.status}  " +
            "in=${record.promptTokens} out=${record.completionTokens}$credits"
    }

    private fun parse(line: String): CallRecord? = runCatching {
        val json = JSONObject(line)
        CallRecord(
            time = json.optLong("time"),
            region = json.optString("region"),
            model = json.optString("model"),
            status = json.optInt("status"),
            promptTokens = json.optInt("prompt_tokens"),
            completionTokens = json.optInt("completion_tokens"),
            credits = json.optString("credits").takeIf { it.isNotBlank() },
            note = json.optString("note").takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    private fun trimIfNeeded() {
        val lines = file.readLines()
        if (lines.size <= MAX_LINES) return
        file.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
    }

    private companion object {
        const val FILE_NAME = "call_log.jsonl"
        const val MAX_LINES = 2000
    }
}
