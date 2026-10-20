package com.wbhub.app.bridge

import android.content.Context
import com.wbhub.app.proto.Wire

/**
 * 转发服务的可调参数（端口 / 共享密钥 / 是否启用）。
 * 这些值不需要加密，但与凭证分开存储，便于「API 平台」页直接展示。
 */
class BridgePrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt(KEY_PORT, Wire.DEFAULT_PORT)
        set(value) {
            prefs.edit().putInt(KEY_PORT, value).apply()
        }

    var secret: String
        get() = prefs.getString(KEY_SECRET, Wire.DEFAULT_SECRET) ?: Wire.DEFAULT_SECRET
        set(value) {
            prefs.edit().putString(KEY_SECRET, value).apply()
        }

    var serviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SERVICE, value).apply()
        }

    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OVERLAY, value).apply()
        }

    var selectedRegion: String
        get() = prefs.getString(KEY_REGION, null) ?: ""
        set(value) {
            prefs.edit().putString(KEY_REGION, value).apply()
        }

    /** 内置对话页最近使用的模型名。 */
    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) {
            prefs.edit().putString(KEY_MODEL, value).apply()
        }

    fun endpoint(): String = Wire.localEndpoint(port)

    fun modelsEndpoint(): String = Wire.modelsEndpoint(port)

    private companion object {
        const val PREFS_NAME = "wbhub_bridge"
        const val KEY_PORT = "port"
        const val KEY_SECRET = "secret"
        const val KEY_SERVICE = "service_enabled"
        const val KEY_OVERLAY = "overlay_enabled"
        const val KEY_REGION = "selected_region"
        const val KEY_MODEL = "chat_model"
        const val DEFAULT_MODEL = "auto"
    }
}
