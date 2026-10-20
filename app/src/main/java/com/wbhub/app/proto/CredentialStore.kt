package com.wbhub.app.proto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 一次完整登录后上游颁发的原生凭证。
 *
 * WorkBuddy 的凭证不是静态 API Key，而是浏览器登录换来的
 * `accessToken` + `refreshToken` 对；`uid` / `domain` / `enterpriseId`
 * 必须随每个请求一起放进身份头，否则上游会拒绝或按错误套餐计费。
 */
data class Account(
    val region: Region,
    val accessToken: String,
    val refreshToken: String,
    /** access_token 过期时间（毫秒时间戳），0 表示未知。 */
    val expiresAt: Long,
    /** refresh_token 过期时间（毫秒时间戳），0 表示未知。 */
    val refreshExpiresAt: Long,
    val domain: String,
    val uid: String,
    val enterpriseId: String,
)

/**
 * 加密持久化登录凭证。国内版与国际版各自独立保存，互不覆盖。
 */
class CredentialStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** 登录成功后整体落盘。 */
    fun saveAccount(account: Account) {
        val region = account.region
        prefs.edit()
            .putString(keyAccess(region), account.accessToken)
            .putString(keyRefresh(region), account.refreshToken)
            .putLong(keyExpires(region), account.expiresAt)
            .putLong(keyRefreshExpires(region), account.refreshExpiresAt)
            .putString(keyDomain(region), account.domain)
            .putString(keyUid(region), account.uid)
            .putString(keyEnterprise(region), account.enterpriseId)
            .apply()
    }

    /** 刷新后只更新令牌部分，账号身份保持不变。 */
    fun updateTokens(
        region: Region,
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
        refreshExpiresAt: Long,
    ) {
        prefs.edit()
            .putString(keyAccess(region), accessToken)
            .putString(keyRefresh(region), refreshToken)
            .putLong(keyExpires(region), expiresAt)
            .putLong(keyRefreshExpires(region), refreshExpiresAt)
            .apply()
    }

    fun account(region: Region): Account? {
        val access = prefs.getString(keyAccess(region), null)?.takeIf { it.isNotBlank() }
            ?: return null
        return Account(
            region = region,
            accessToken = access,
            refreshToken = prefs.getString(keyRefresh(region), null).orEmpty(),
            expiresAt = prefs.getLong(keyExpires(region), 0L),
            refreshExpiresAt = prefs.getLong(keyRefreshExpires(region), 0L),
            domain = prefs.getString(keyDomain(region), null).orEmpty()
                .ifBlank { region.apiDomain },
            uid = prefs.getString(keyUid(region), null).orEmpty(),
            enterpriseId = prefs.getString(keyEnterprise(region), null).orEmpty(),
        )
    }

    fun isLoggedIn(region: Region): Boolean = account(region) != null

    /** 过期前 5 分钟视为需要刷新；过期时间未知时不主动刷新。 */
    fun needsRefresh(region: Region): Boolean {
        val expiresAt = prefs.getLong(keyExpires(region), 0L)
        if (expiresAt <= 0L) return false
        return System.currentTimeMillis() >= expiresAt - REFRESH_AHEAD_MS
    }

    fun clear(region: Region) {
        prefs.edit()
            .remove(keyAccess(region))
            .remove(keyRefresh(region))
            .remove(keyExpires(region))
            .remove(keyRefreshExpires(region))
            .remove(keyDomain(region))
            .remove(keyUid(region))
            .remove(keyEnterprise(region))
            .apply()
    }

    private fun keyAccess(region: Region) = "access_${region.id}"
    private fun keyRefresh(region: Region) = "refresh_${region.id}"
    private fun keyExpires(region: Region) = "expires_${region.id}"
    private fun keyRefreshExpires(region: Region) = "refresh_expires_${region.id}"
    private fun keyDomain(region: Region) = "domain_${region.id}"
    private fun keyUid(region: Region) = "uid_${region.id}"
    private fun keyEnterprise(region: Region) = "enterprise_${region.id}"

    private companion object {
        const val PREFS_NAME = "wbhub_credentials"
        const val REFRESH_AHEAD_MS = 5 * 60_000L
    }
}
