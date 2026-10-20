package com.wbhub.app.data

import com.wbhub.app.proto.CredentialStore
import com.wbhub.app.proto.Region
import com.wbhub.app.proto.TokenEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 登录：把凭证写进 [CredentialStore]。
 *
 * 官方没有面向个人的浏览器授权流程，只有两条路子：
 * - 个人账号：平台控制台生成的静态 API Key（走 `X-Api-Key` 头）；
 * - 企业账号：企业应用控制台发放的 client_id + client_secret，走 client_credentials 换 token。
 *
 * 两条都只用一次网络请求，不需要本地回调端口，原先的 loopback 监听与 PKCE 已删除。
 */
class Login(private val credentials: CredentialStore) {

    /** 个人账号：API Key 直接保存，不需要联网校验。 */
    fun loginWithApiKey(region: Region, apiKey: String) {
        val trimmed = apiKey.trim()
        require(trimmed.isNotEmpty()) { "API Key 不能为空" }
        credentials.saveApiKey(region, trimmed)
    }

    /** 企业账号：用 Client ID / Secret 换 access_token，成功后才落盘。 */
    suspend fun loginWithClientCredentials(
        region: Region,
        clientId: String,
        clientSecret: String,
    ): Unit = withContext(Dispatchers.IO) {
        val id = clientId.trim()
        val secret = clientSecret.trim()
        require(id.isNotEmpty()) { "Client ID 不能为空" }
        require(secret.isNotEmpty()) { "Client Secret 不能为空" }

        val result = TokenEndpoint.request(region, id, secret)
        val token = result.accessToken
            ?: throw IllegalStateException("换取 Token 失败：${result.error ?: "未知错误"}")
        credentials.saveClientCredentials(region, id, secret, token, result.expiresInSec)
    }

    /**
     * 用已保存的企业凭证重新换 token。个人 API Key 模式无需刷新，直接返回 null。
     */
    suspend fun refresh(region: Region): String? = withContext(Dispatchers.IO) {
        val id = credentials.clientId(region) ?: return@withContext null
        val secret = credentials.clientSecret(region) ?: return@withContext null
        val result = TokenEndpoint.request(region, id, secret)
        val token = result.accessToken ?: return@withContext null
        credentials.updateAccessToken(region, token, result.expiresInSec)
        token
    }
}
