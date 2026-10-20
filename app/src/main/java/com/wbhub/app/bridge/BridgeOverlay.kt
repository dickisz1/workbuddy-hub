package com.wbhub.app.bridge

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.wbhub.app.R

/**
 * 悬浮窗保活：系统认为 App 仍「可见」，进程优先级升高，降低被 ROM 冻结/回收的概率。
 *
 * 仅靠前台服务（specialUse）实测仍会被部分 ROM 冻结，因此需要用户手动授予悬浮窗权限后叠加此窗口。
 */
class BridgeOverlay(private val context: Context) {

    private var view: View? = null

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
            setText(R.string.overlay_text)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setPadding(24, 12, 24, 12)
            setBackgroundColor(0x9912213A.toInt())
        }

        runCatching {
            manager.addView(text, params)
            view = text
        }
    }

    fun hide() {
        val current = view ?: return
        runCatching { windowManager?.removeView(current) }
        view = null
    }
}
