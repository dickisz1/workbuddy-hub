package com.wbhub.app.data

import com.wbhub.app.proto.Account
import com.wbhub.app.proto.AuthApi
import com.wbhub.app.proto.CredentialStore
import com.wbhub.app.proto.Region
import com.wbhub.app.proto.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * 登录：完全复刻 WorkBuddy 原生凭证流程。
 *
 * 1. [beginSignIn] 申请 state，返回需要在浏览器打开的登录页；
 * 2. [awaitSignIn] 轮询到 `accessToken` / `refreshToken`，再取 `uid` / `enterpriseId`；
 * 3. [refresh] 用 `refreshToken` 静默续期。
 *
 * 没有授权码回调、没有本地监听端口，也不需要用户手工粘贴任何 Key。
 */
class Login(private val credentials: CredentialStore) {

    data class SignIn(val state: String, val authUrl: String)

    /** 第一步：申请 state 与登录页地址。 */
    suspend fun beginSignIn(region: Region): SignIn = withContext(Dispatchers.IO) {
        val result = AuthApi.createState(region)
        val state = result.state ?: throw IllegalStateException(result.error ?: "上游未返回 state")
        val authUrl = result.authUrl ?: throw IllegalStateException(result.error ?: "上游未返回登录页")
        SignIn(state, Wire.withLoginParams(authUrl, randomHex()))
    }

    /** 第二、三步：轮询登录结果与账号信息，成功后落盘。 */
    suspend fun awaitSignIn(
        region: Region,
        state: String,
        onProgress: (String) -> Unit = {},
    ): Account = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + SIGN_IN_TIMEOUT_MS

        onProgress("等待浏览器完成登录…")
        var token: AuthApi.Token? = null
        while (System.currentTimeMillis() < deadline) {
            val current = AuthApi.pollToken(region, state)
            if (!current.accessToken.isNullOrBlank()) {
                token = current
                break
            }
            if (!current.pending) {
                throw IllegalStateException(current.error ?: "登录失败")
            }
            delay(POLL_INTERVAL_MS)
        }
        val signed = token ?: throw IllegalStateException("登录超时，请重新发起登录")

        val access = signed.accessToken
            ?: throw IllegalStateException("上游未返回 accessToken")
        val domain = signed.domain.orEmpty().ifBlank { region.apiDomain }

        onProgress("读取账号信息…")
        var who: AuthApi.Who? = null
        while (System.currentTimeMillis() < deadline) {
            val current = AuthApi.whoAmI(region, state, access, domain)
            if (!current.uid.isNullOrBlank()) {
                who = current
                break
            }
            if (!current.pending) {
                throw IllegalStateException(current.error ?: "读取账号信息失败")
            }
            delay(POLL_INTERVAL_MS)
        }
        val identity = who ?: throw IllegalStateException("登录超时：未能读取账号信息")

        val account = Account(
            region = region,
            accessToken = access,
            refreshToken = signed.refreshToken.orEmpty(),
            expiresAt = signed.expiresAt,
            refreshExpiresAt = signed.refreshExpiresAt,
            domain = domain,
            uid = identity.uid.orEmpty(),
            enterpriseId = identity.enterpriseId.orEmpty(),
        )
        credentials.saveAccount(account)
        account
    }

    /**
     * 静默续期：用 refreshToken 换新的一对令牌。
     * 失败返回 null，由调用方决定是否提示重新登录。
     */
    suspend fun refresh(region: Region): String? = withContext(Dispatchers.IO) {
        val account = credentials.account(region) ?: return@withContext null
        if (account.refreshToken.isBlank()) return@withContext null
        val result = AuthApi.refresh(region, account.refreshToken, account.domain, account.enterpriseId)
        val access = result.accessToken ?: return@withContext null
        credentials.updateTokens(
            region = region,
            accessToken = access,
            refreshToken = result.refreshToken.orEmpty().ifBlank { account.refreshToken },
            expiresAt = result.expiresAt,
            refreshExpiresAt = result.refreshExpiresAt,
        )
        access
    }

    private fun randomHex(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SIGN_IN_TIMEOUT_MS = 5 * 60_000L
        const val POLL_INTERVAL_MS = 1_500L
    }
}
