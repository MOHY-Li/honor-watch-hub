package dev.watchhub.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import dev.watchhub.app.hub.Engine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台服务：本机通知中枢的「心跳」。
 *
 * 原来的架构里它维护一条到 PC 的 SSE 长连接；现在服务端就在进程内，
 * 它改成一个简单的调度循环：每 20 秒调用一次 Engine.tick()，
 * 引擎自己判断每个源是否到期。
 *
 * 为什么仍然是前台服务：
 *  - 只有前台服务能在息屏 / 后台时持续跑网络
 *  - 一个 MIN 优先级的常驻通知，用户几乎无感
 *
 * 为什么不用 WorkManager 做周期任务：
 * 最小周期是 15 分钟，而 cron 提醒和「抢票/补货」需要分钟级。
 * 两条腿都要：前台服务负责分钟级，WorkManager 作为被系统杀掉后的兜底拉起。
 */
class HubService : Service() {

    private lateinit var engine: Engine
    private var thread: Thread? = null
    private val stopRequested = AtomicBoolean(false)

    @Volatile private var awake = false

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        engine = Engine(this)
        engine.start()
        startForeground(FOREGROUND_ID, Notifier.serviceNotification(this, "中枢已启动"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested.set(true)
            engine.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (thread == null) {
            stopRequested.set(false)
            thread = Thread({ loop() }, "watchhub-engine").apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    private fun loop() {
        log("中枢启动，共 ${engine.statuses().size} 个源")
        while (!stopRequested.get()) {
            val started = System.currentTimeMillis()
            val produced = try {
                engine.tick()
            } catch (t: Throwable) {
                Log.e(TAG, "tick failed", t)
                0
            }
            if (produced > 0) {
                update("刚刚推送 $produced 条")
            } else {
                update(summary())
            }
            // 目标 20s 一拍：cron 需要分钟级精度，20s 足够且不至于太耗电
            val elapsed = System.currentTimeMillis() - started
            sleep((TICK_MS - elapsed).coerceAtLeast(1_000L))
        }
        update("已停止")
    }

    private fun summary(): String {
        val st = engine.statuses()
        val ok = st.count { it.enabled && it.lastError == null && it.lastOk > 0 }
        val enabled = st.count { it.enabled }
        return if (enabled == 0) "没有启用的源" else "运行中 · $ok/$enabled 个源正常"
    }

    private fun sleep(ms: Long) {
        try {
            // 分片睡眠，停止指令能及时响应
            var left = ms
            while (left > 0 && !stopRequested.get()) {
                val chunk = minOf(left, 1_000L)
                Thread.sleep(chunk)
                left -= chunk
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun update(text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(FOREGROUND_ID, Notifier.serviceNotification(this, text))
        } catch (_: Throwable) {
        }
    }

    private fun log(s: String) {
        Log.i(TAG, s)
        LogBus.post(s)
    }

    override fun onDestroy() {
        stopRequested.set(true)
        engine.stop()
        thread?.interrupt()
        releaseWake()
        super.onDestroy()
    }

    /** 仅在真正要联网检查时短暂持有，避免被判定成耗电应用。 */
    private fun acquireWake(ms: Long = 60_000L) {
        if (awake) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchHub:engine").apply {
                setReferenceCounted(false)
                acquire(ms)
            }
            awake = true
        }
    }

    private fun releaseWake() {
        awake = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "WatchHubService"
        const val ACTION_STOP = "dev.watchhub.app.STOP"
        private const val FOREGROUND_ID = 42
        private const val TICK_MS = 20_000L

        fun start(ctx: Context) {
            val i = Intent(ctx, HubService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, HubService::class.java))
        }
    }
}



