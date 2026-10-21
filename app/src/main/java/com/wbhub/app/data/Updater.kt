package com.wbhub.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用自更新。
 *
 * 数据源是 GitHub Release 的两个资产：
 * - `version.json`：CI 写入的 `{versionCode, versionName, tag}`，用于版本比较；
 * - `app-debug.apk`：安装包本体。
 *
 * Android 不允许静默安装，所以这里只做「检查 → 下载 → 拉起系统安装器」，
 * 最后一步必须由用户在系统弹窗里确认。
 */
class Updater(private val context: Context) {

    data class Release(
        val versionCode: Int,
        val versionName: String,
        val tag: String,
        val apkUrl: String,
    )

    /** 本机版本号；取不到时返回 0，此时任何 release 都算新。 */
    fun installedVersionCode(): Int = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
    }.getOrDefault(0)

    fun installedVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    /** 返回比本机新的版本；已是最新或网络失败返回 null。 */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val latest = getJson(LATEST_RELEASE_URL) ?: return@withContext null
        val assets = latest.optJSONArray("assets") ?: return@withContext null

        var versionUrl: String? = null
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            when (asset.optString("name")) {
                VERSION_ASSET -> versionUrl = asset.optString("browser_download_url")
                APK_ASSET -> apkUrl = asset.optString("browser_download_url")
            }
        }
        val metaUrl = versionUrl?.takeIf { it.isNotBlank() } ?: return@withContext null
        val apk = apkUrl?.takeIf { it.isNotBlank() } ?: return@withContext null

        val meta = getJson(metaUrl) ?: return@withContext null
        val code = meta.optInt("versionCode", 0)
        if (code <= installedVersionCode()) return@withContext null
        Release(
            versionCode = code,
            versionName = meta.optString("versionName"),
            tag = latest.optString("tag_name"),
            apkUrl = apk,
        )
    }

    /** 下载到私有缓存目录，返回落盘文件。 */
    suspend fun download(release: Release): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
        // 清掉旧包，避免缓存里堆多个版本
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "wbhub-${release.versionCode}.apk")

        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (conn.responseCode !in 200..299) return@withContext null
            conn.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output, BUFFER_SIZE) }
            }
            target.takeIf { it.length() > 0 }
        } catch (e: Exception) {
            target.delete()
            null
        } finally {
            conn.disconnect()
        }
    }

    /** 安装器 Intent；文件通过 FileProvider 暴露，调用方需带 FLAG_GRANT_READ_URI_PERMISSION。 */
    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, authority(), file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 「安装未知应用」是否已对本应用放开。 */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** 跳转到本应用的「安装未知应用」授权页。 */
    fun requestInstallPermissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun authority(): String = "${context.packageName}.fileprovider"

    private fun getJson(url: String): JSONObject? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            runCatching { JSONObject(text) }.getOrNull()
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/dickisz1/workbuddy-hub/releases/latest"
        const val VERSION_ASSET = "version.json"
        const val APK_ASSET = "app-debug.apk"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val UPDATE_DIR = "updates"
        const val USER_AGENT = "WorkBuddyHub-Updater"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 120_000
        const val BUFFER_SIZE = 64 * 1024
    }
}
