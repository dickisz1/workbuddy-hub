package com.wbhub.app.proto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 每日自动任务：签到、活跃上报。由前台服务内的协程定时触发。
 */
class GrowthClient(
    private val upstream: UpstreamClient,
    private val credentials: CredentialStore,
) {

    suspend fun signIn(region: Region): Boolean = withContext(Dispatchers.IO) {
        val response = upstream.jsonRequest(region, Wire.UPSTREAM_SIGNIN_PATH, "POST", "{}")
        response.code in 200..299
    }

    suspend fun reportActive(region: Region): Boolean = withContext(Dispatchers.IO) {
        val payload = """{"client":"${Wire.CLIENT_TYPE}","version":"${Wire.APP_VERSION}"}"""
        val response = upstream.jsonRequest(region, Wire.UPSTREAM_ACTIVE_PATH, "POST", payload)
        response.code in 200..299
    }

    /** 对指定区域依次跑一遍每日任务，返回执行结果摘要。 */
    suspend fun runDaily(regions: List<Region>): List<String> = buildList {
        for (region in regions) {
            if (!credentials.isLoggedIn(region)) continue
            add("${region.displayName} 签到: ${if (signIn(region)) "成功" else "失败"}")
            add("${region.displayName} 活跃上报: ${if (reportActive(region)) "成功" else "失败"}")
        }
    }
}
