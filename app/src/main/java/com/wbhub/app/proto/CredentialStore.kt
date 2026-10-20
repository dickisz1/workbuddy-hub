package com.wbhub.app.proto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * 某区域当前的登录方式。
 *
 * 官方只有两条真实路子：个人账号给静态 API Key，企业账号给 client_id + client_secret 换
 * access_token。没有面向个人的浏览器授权流程，所以这里没有「授权码」这一态。
 */
enum class AuthMode { NONE, API_KEY, CLIENT_CREDENTIALS }

/** 企业凭证换来的令牌。个人 API Key 模式不产生它。 */
data class Account(
    val region: Region,
    val accessToken: String,
    val expiresAt: Long,
)

/**
 * 加密持久化登录凭证：API Key、企业 Client 凭证、access_token 与设备指纹。
 * 国内版与国际版各自独立保存，互不覆盖。
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

    /** 个人账号：只存 API Key，并清掉可能残留的企业凭证。 */
    fun saveApiKey(region: Region, apiKey: String) {
        prefs.edit()
            .putString(keyApiKey(region), apiKey)
            .remove(keyClientId(region))
            .remove(keyClientSecret(region))
            .remove(keyAccess(region))
            .remove(keyExpires(region))
            .apply()
    }

    /** 企业账号：存 Client ID / Secret 与换来的 access_token，并清掉 API Key。 */
    fun saveClientCredentials(
        region: Region,
        clientId: String,
        clientSecret: String,
        accessToken: String,
        expiresInSec: Long,
    ) {
        prefs.edit()
            .putString(keyClientId(region), clientId)
            .putString(keyClientSecret(region), clientSecret)
            .putString(keyAccess(region), accessToken)
            .putLong(keyExpires(region), System.currentTimeMillis() + expiresInSec * 1000L)
            .remove(keyApiKey(region))
            .apply()
    }

    /** 重新换到 access_token 后只更新令牌，不动 Client 凭证。 */
    fun updateAccessToken(region: Region, accessToken: String, expiresInSec: Long) {
        prefs.edit()
            .putString(keyAccess(region), accessToken)
            .putLong(keyExpires(region), System.currentTimeMillis() + expiresInSec * 1000L)
            .apply()
    }

    fun apiKey(region: Region): String? = prefs.getString(keyApiKey(region), null)

    fun clientId(region: Region): String? = prefs.getString(keyClientId(region), null)

    fun clientSecret(region: Region): String? = prefs.getString(keyClientSecret(region), null)

    fun accessToken(region: Region): String? = prefs.getString(keyAccess(region), null)

    fun mode(region: Region): AuthMode = when {
        !apiKey(region).isNullOrBlank() -> AuthMode.API_KEY
        !accessToken(region).isNullOrBlank() -> AuthMode.CLIENT_CREDENTIALS
        else -> AuthMode.NONE
    }

    /** 仅企业凭证模式有 access_token，才有「过期」一说。 */
    fun account(region: Region): Account? {
        val access = accessToken(region) ?: return null
        return Account(
            region = region,
            accessToken = access,
            expiresAt = prefs.getLong(keyExpires(region), 0L),
        )
    }

    fun isLoggedIn(region: Region): Boolean = mode(region) != AuthMode.NONE

    /** 过期前 60s 视为需要重新换取。 */
    fun needsRefresh(region: Region): Boolean {
        val expiresAt = prefs.getLong(keyExpires(region), 0L)
        if (expiresAt == 0L) return false
        return System.currentTimeMillis() > expiresAt - REFRESH_AHEAD_MS
    }

    fun clear(region: Region) {
        prefs.edit()
            .remove(keyApiKey(region))
            .remove(keyClientId(region))
            .remove(keyClientSecret(region))
            .remove(keyAccess(region))
            .remove(keyExpires(region))
            .apply()
    }

    /** 稳定的设备指纹，首次生成后不再变化。 */
    fun deviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    private fun keyApiKey(region: Region) = "api_key_${region.id}"
    private fun keyClientId(region: Region) = "client_id_${region.id}"
    private fun keyClientSecret(region: Region) = "client_secret_${region.id}"
    private fun keyAccess(region: Region) = "access_${region.id}"
    private fun keyExpires(region: Region) = "expires_${region.id}"

    private companion object {
        const val PREFS_NAME = "wbhub_credentials"
        const val KEY_DEVICE_ID = "device_id"
        const val REFRESH_AHEAD_MS = 60_000L
    }
}
