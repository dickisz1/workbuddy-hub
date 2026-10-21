package com.wbhub.app.bridge

import android.content.Context
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.wbhub.app.proto.CredentialStore
import com.wbhub.app.proto.Region
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 悬浮窗保活 + 状态看板。
 *
 * 保活作用不变（系统认为 App 仍「可见」，进程优先级升高）；同时把 [BridgeStats] 的实时数据
 * 显示出来：服务是否在监听、当前是否转发中、网络是否可用、今日调用与错误、最近一次调用的
 * 模型/状态码/耗时，以及调用方（按 `User-Agent` 归类）。
 *
 * 每秒重绘一次。说明一个能力边界：本地 HTTP 请求只能拿到 UA，拿不到对方包名，
 * 因此「哪个 App 在调用」只能显示到 UA 归类的粒度。
 */
class BridgeOverlay(
    private val context: Context,
    private val credentials: CredentialStore,
    private val prefs: BridgePrefs,
) {

    private var view: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val windowManager: WindowManager?
        get() = context.getSystemService(WindowManager::class.java)

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun show() {
        if (view != null || !canShow()) return
        val manager = windowManager ?: return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 160
        }

        val text = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 14, 24, 14)
            setBackgroundColor(0xCC12213A.toInt())
            setLineSpacing(4f, 1f)
        }

        runCatching {
            manager.addView(text, params)
            view = text
            render()
            handler.removeCallbacks(ticker)
            handler.postDelayed(ticker, REFRESH_MS)
        }
    }

    fun hide() {
        handler.removeCallbacks(ticker)
        val current = view ?: return
        runCatching { windowManager?.removeView(current) }
        view = null
    }

    private fun render() {
        val text = view ?: return
        val snap = BridgeStats.snapshot()
        val lines = ArrayList<String>(6)

        val running = BridgeService.running
        lines += if (running) "WB Hub · 监听 :${prefs.port}" else "WB Hub · 未启动"
        lines += statusLine(running, snap)

        val online = networkAvailable()
        lines += if (online) "网络：正常" else "网络：已断开（上游不可达）"
        lines += "今日 ${snap.callsToday} 次 · 错误 ${snap.errorsToday}"

        if (snap.lastAt > 0) {
            val at = timeFormat.format(Date(snap.lastAt))
            val model = snap.lastModel ?: "-"
            lines += "最近 $at $model · ${snap.lastStatus} · ${snap.lastMs}ms"
            snap.lastClient?.let { lines += "调用方 $it" }
        }
        snap.lastError?.let { lines += "最近错误 $it" }

        text.text = lines.joinToString("\n")
    }

    private fun statusLine(running: Boolean, snap: BridgeStats.Snapshot): String = when {
        !running -> "状态：服务未启动"
        !loggedIn() -> "状态：上游未登录"
        !networkAvailable() -> "状态：网络断开"
        snap.inflight > 0 -> "状态：转发中 ×${snap.inflight}"
        else -> "状态：空闲"
    }

    private fun loggedIn(): Boolean = Region.entries.any { credentials.isLoggedIn(it) }

    private fun networkAvailable(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        const val REFRESH_MS = 1000L
    }
}
