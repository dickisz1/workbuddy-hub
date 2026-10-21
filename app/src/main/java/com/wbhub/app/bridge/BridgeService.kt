package com.wbhub.app.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wbhub.app.App
import com.wbhub.app.MainActivity
import com.wbhub.app.R
import com.wbhub.app.proto.CredentialStore
import com.wbhub.app.proto.GrowthClient
import com.wbhub.app.proto.Region
import com.wbhub.app.proto.UpstreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务：承载 [BridgeServer]，保持转发链路常驻。
 *
 * `foregroundServiceType="specialUse"`：需要在 AndroidManifest 中同步声明
 * FOREGROUND_SERVICE_SPECIAL_USE 权限与 PROPERTY_SPECIAL_USE_FGS_SUBTYPE。
 * 实测仅靠前台服务仍可能被部分 ROM 冻结，因此配合 [BridgeOverlay] 悬浮窗提升进程优先级。
 */
class BridgeService : Service() {

    private lateinit var prefs: BridgePrefs
    private lateinit var credentials: CredentialStore
    private lateinit var callLog: CallLogStore
    private lateinit var upstream: UpstreamClient
    private lateinit var growth: GrowthClient

    private var server: BridgeServer? = null
    private var overlay: BridgeOverlay? = null
    private var dailyJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val app = application as App
        prefs = BridgePrefs(this)
        credentials = app.credentials
        callLog = CallLogStore(this)
        upstream = UpstreamClient(credentials)
        growth = GrowthClient(upstream, credentials)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_OVERLAY_ON -> prefs.overlayEnabled = true
            ACTION_OVERLAY_OFF -> prefs.overlayEnabled = false
        }
        startForegroundCompat()
        startServer()
        if (prefs.overlayEnabled) attachOverlay() else detachOverlay()
        scheduleDailyTasks()
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        dailyJob?.cancel()
        server?.stop()
        server = null
        overlay?.hide()
        overlay = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------

    private fun startServer() {
        if (server?.isRunning == true) return
        val instance = BridgeServer(
            port = prefs.port,
            secret = prefs.secret,
            credentials = credentials,
            upstream = upstream,
            callLog = callLog,
        )
        instance.onEvent = { message -> logLine(message) }
        runCatching { instance.start() }
            .onFailure { logLine("启动失败: ${it.message}") }
        server = instance
    }

    private fun attachOverlay() {
        if (overlay == null) overlay = BridgeOverlay(this)
        overlay?.show()
    }

    private fun detachOverlay() {
        overlay?.hide()
        overlay = null
    }

    /** 每日自动任务：签到 + 活跃上报。 */
    private fun scheduleDailyTasks() {
        if (dailyJob?.isActive == true) return
        dailyJob = scope.launch {
            while (isActive) {
                val regions = Region.entries.filter { credentials.isLoggedIn(it) }
                if (regions.isNotEmpty()) {
                    growth.runDaily(regions).forEach { logLine("每日任务 $it") }
                }
                delay(DAILY_INTERVAL_MS)
            }
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification(prefs.port)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(port: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, port))
            .setSmallIcon(R.drawable.ic_tab_platform)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_desc)
            },
        )
    }

    private fun logLine(message: String) {
        callLog.append(
            CallRecord(
                time = System.currentTimeMillis(),
                kind = CallLogStore.KIND_SERVICE,
                region = "",
                model = "-",
                status = 0,
                durationMs = 0,
                promptTokens = 0,
                completionTokens = 0,
                credits = null,
                note = message,
                raw = null,
            ),
        )
    }

    companion object {
        const val ACTION_START = "com.wbhub.app.action.START_BRIDGE"
        const val ACTION_STOP = "com.wbhub.app.action.STOP_BRIDGE"
        const val ACTION_OVERLAY_ON = "com.wbhub.app.action.OVERLAY_ON"
        const val ACTION_OVERLAY_OFF = "com.wbhub.app.action.OVERLAY_OFF"

        private const val CHANNEL_ID = "wbhub_bridge"
        private const val NOTIFICATION_ID = 1001
        private const val DAILY_INTERVAL_MS = 6 * 60 * 60 * 1000L

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            dispatch(context, ACTION_START)
        }

        fun stop(context: Context) {
            dispatch(context, ACTION_STOP)
            running = false
        }

        /** 悬浮窗开关：服务在跑时立即生效，否则随服务一起启动。 */
        fun setOverlay(context: Context, enabled: Boolean) {
            dispatch(context, if (enabled) ACTION_OVERLAY_ON else ACTION_OVERLAY_OFF)
        }

        private fun dispatch(context: Context, action: String) {
            val intent = Intent(context, BridgeService::class.java).setAction(action)
            if (action == ACTION_STOP) {
                context.startService(intent)
            } else {
                context.startForegroundService(intent)
            }
        }
    }
}
