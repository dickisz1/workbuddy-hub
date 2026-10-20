package com.wbhub.app.proto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

data class Account(
    val region: Region,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long,
)

/**
 * 加密持久化 access / refresh token 与设备指纹。
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

    fun saveAccount(region: Region, accessToken: String, refreshToken: String?, expiresInSec: Long) {
        prefs.edit()
            .putString(keyAccess(region), accessToken)
            .putString(keyRefresh(region), refreshToken)
            .putLong(keyExpires(region), System.currentTimeMillis() + expiresInSec * 1000L)
            .apply()
    }

    fun account(region: Region): Account? {
        val access = prefs.getString(keyAccess(region), null) ?: return null
        return Account(
            region = region,
            accessToken = access,
            refreshToken = prefs.getString(keyRefresh(region), null),
            expiresAt = prefs.getLong(keyExpires(region), 0L),
        )
    }

    fun accessToken(region: Region): String? = prefs.getString(keyAccess(region), null)

    fun refreshToken(region: Region): String? = prefs.getString(keyRefresh(region), null)

    fun isLoggedIn(region: Region): Boolean = !accessToken(region).isNullOrBlank()

    /** 过期前 60s 视为需要刷新。 */
    fun needsRefresh(region: Region): Boolean {
        val expiresAt = prefs.getLong(keyExpires(region), 0L)
        if (expiresAt == 0L) return false
        return System.currentTimeMillis() > expiresAt - REFRESH_AHEAD_MS
    }

    fun clear(region: Region) {
        prefs.edit()
            .remove(keyAccess(region))
            .remove(keyRefresh(region))
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

    private fun keyAccess(region: Region) = "access_${region.id}"
    private fun keyRefresh(region: Region) = "refresh_${region.id}"
    private fun keyExpires(region: Region) = "expires_${region.id}"

    private companion object {
        const val PREFS_NAME = "wbhub_credentials"
        const val KEY_DEVICE_ID = "device_id"
        const val REFRESH_AHEAD_MS = 60_000L
    }
}
