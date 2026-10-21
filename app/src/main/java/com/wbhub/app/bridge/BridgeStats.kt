package com.wbhub.app.bridge

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 转发链路的实时统计，供悬浮窗展示。
 *
 * 只统计 `/v1/chat/completions`（模型列表之类的探测不计入，否则「今日调用」会被客户端轮询刷高）。
 * 读写都在同一把锁内，频率很低：每次请求两次，悬浮窗每秒读一次。
 */
object BridgeStats {

    data class Snapshot(
        /** 当前正在处理中的请求数。 */
        val inflight: Int,
        val callsToday: Int,
        val errorsToday: Int,
        /** 最近一次完成的调用时间，0 表示今天还没有。 */
        val lastAt: Long,
        val lastClient: String?,
        val lastModel: String?,
        val lastStatus: Int,
        val lastMs: Long,
        val lastError: String?,
    )

    private val lock = Any()
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private var day = ""
    private var inflight = 0
    private var callsToday = 0
    private var errorsToday = 0
    private var lastAt = 0L
    private var lastClient: String? = null
    private var lastModel: String? = null
    private var lastStatus = 0
    private var lastMs = 0L
    private var lastError: String? = null

    fun begin(client: String?) = synchronized(lock) {
        rollover()
        inflight++
        if (!client.isNullOrBlank()) lastClient = client
    }

    fun finish(model: String, status: Int, durationMs: Long, note: String?) = synchronized(lock) {
        rollover()
        inflight = (inflight - 1).coerceAtLeast(0)
        callsToday++
        lastAt = System.currentTimeMillis()
        lastModel = model
        lastStatus = status
        lastMs = durationMs
        if (status !in 200..299) {
            errorsToday++
            lastError = when {
                status == 0 -> note ?: "请求未完成"
                else -> "HTTP $status" + (note?.let { " $it" } ?: "")
            }
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        rollover()
        Snapshot(inflight, callsToday, errorsToday, lastAt, lastClient, lastModel, lastStatus, lastMs, lastError)
    }

    /** 跨天后把当日计数清零，长期挂后台也不会显示成上周的数字。 */
    private fun rollover() {
        val today = dayFormat.format(Date())
        if (today == day) return
        day = today
        callsToday = 0
        errorsToday = 0
        lastAt = 0L
        lastError = null
    }

    /**
     * 把 `User-Agent` 归成能认出来的调用方。
     *
     * 本地 HTTP 只能看到 UA，看不到对方的应用包名——要精确到 App 需要读取
     * `/proc/net` 或使用 VPN 抓包，权限与代价都不合适，所以这里只做 UA 归类。
     */
    fun describeClient(userAgent: String?): String {
        if (userAgent.isNullOrBlank()) return "未标 UA"
        val ua = userAgent.lowercase(Locale.ROOT)
        return when {
            ua.contains("curl") -> "curl"
            ua.contains("python") || ua.contains("httpx") || ua.contains("requests") -> "Python"
            ua.contains("okhttp") -> "OkHttp"
            ua.contains("dalvik") -> "本机 App"
            ua.contains("undici") || ua.contains("node") || ua.contains("axios") -> "Node"
            ua.contains("edg") -> "Edge"
            ua.contains("chrome") -> "Chrome"
            ua.contains("firefox") -> "Firefox"
            ua.contains("dart") -> "Flutter"
            ua.contains("go-http") -> "Go"
            else -> userAgent.take(20)
        }
    }
}
