package com.wbhub.app.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject,
)

/**
 * 内置 Agent 的工具集：读文件、写文件、执行 shell。
 *
 * 所有文件操作被限制在应用私有目录下的 [root] 内，避免越权访问。
 */
class AgentTools(context: Context) {

    val root: File = File(context.filesDir, WORKSPACE_DIR).apply { mkdirs() }

    fun specs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "read_file",
            description = "读取工作区内文本文件的内容",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("path", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("path")),
        ),
        ToolSpec(
            name = "write_file",
            description = "把内容写入工作区内的文件（覆盖写）",
            parameters = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("path", JSONObject().put("type", "string"))
                        .put("content", JSONObject().put("type", "string")),
                )
                .put("required", JSONArray().put("path").put("content")),
        ),
        ToolSpec(
            name = "bash",
            description = "在工作区内执行一条 shell 命令并返回输出",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("command", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("command")),
        ),
    )

    /** 转成 OpenAI function-calling 的 tools 数组。 */
    fun openAiSchemas(): JSONArray {
        val array = JSONArray()
        specs().forEach { spec ->
            array.put(
                JSONObject().put(
                    "type",
                    "function",
                ).put(
                    "function",
                    JSONObject()
                        .put("name", spec.name)
                        .put("description", spec.description)
                        .put("parameters", spec.parameters),
                ),
            )
        }
        return array
    }

    fun execute(name: String, arguments: JSONObject): String = runCatching {
        when (name) {
            "read_file" -> readFile(arguments.optString("path"))
            "write_file" -> writeFile(arguments.optString("path"), arguments.optString("content"))
            "bash" -> bash(arguments.optString("command"))
            else -> "未知工具: $name"
        }
    }.getOrElse { "工具执行失败: ${it.message}" }

    private fun readFile(path: String): String {
        val file = resolve(path)
        if (!file.exists()) return "文件不存在: $path"
        if (file.length() > MAX_READ_BYTES) return "文件过大，已截断读取前 ${MAX_READ_BYTES} 字节"
        return file.readText()
    }

    private fun writeFile(path: String, content: String): String {
        val file = resolve(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "已写入 ${file.relativeTo(root).path}（${content.length} 字符）"
    }

    private fun bash(command: String): String {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(COMMAND_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return "命令超时（${COMMAND_TIMEOUT_SEC}s）:\n$output"
        }
        return output.take(MAX_OUTPUT_CHARS)
    }

    /** 路径解析：一律相对 [root]，并校验 canonical path 不越界。 */
    private fun resolve(path: String): File {
        val target = if (path.startsWith("/")) File(root, path.trimStart('/')) else File(root, path)
        val canonicalRoot = root.canonicalPath
        val canonicalTarget = target.canonicalPath
        if (!canonicalTarget.startsWith(canonicalRoot)) {
            throw IllegalArgumentException("路径越界: $path")
        }
        return target
    }

    private companion object {
        const val WORKSPACE_DIR = "agent-workspace"
        const val MAX_READ_BYTES = 256 * 1024
        const val MAX_OUTPUT_CHARS = 8 * 1024
        const val COMMAND_TIMEOUT_SEC = 30L
    }
}
