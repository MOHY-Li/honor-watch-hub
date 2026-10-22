package dev.watchhub.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dev.watchhub.app.App
import dev.watchhub.app.Container
import dev.watchhub.app.core.LogBus
import dev.watchhub.app.delivery.Notifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台服务：源轮询的「心跳」。
 *
 * 它不再是唯一的调度器（任务到期由 AlarmManager 负责），只承担：
 *  - 周期检查数据源（分钟级精度）
 *  - 写心跳，让看门狗知道它还活着
 *  - 派发 outbox、排下一次闹钟
 *
 * 这样即使荣耀把服务冻结，闹钟仍能把进程叫起来；反过来即使闹钟被清掉，
 * 服务还活着时每 20 秒也会重新排上。
 */
class HubService : Service() {

    private lateinit var c: Container
    private var thread: Thread? = null
    private val stopRequested = AtomicBoolean(false)
    private var wake: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        c = App.container(this)
        Notifier.ensureChannels(this)
        startForeground(FOREGROUND_ID, Notifier.serviceNotification(this, "中枢已启动"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested.set(true)
            c.settings.wantRun = false
            c.scheduler.cancel()
            update("已停止")
            stopSelf()
            return START_NOT_STICKY
        }
        c.settings.wantRun = true
        if (thread == null) {
            stopRequested.set(false)
            thread = Thread({ loop() }, "watchhub-hub").apply {
                isDaemon = true
                start()
            }
        }
        return START_STICKY
    }

    private fun loop() {
        LogBus.post("中枢启动")
        var lastMaintenance = 0L
        var lastCalendar = 0L
        while (!stopRequested.get()) {
            val started = c.clock.now()
            try {
                keepAwake()
                c.settings.heartbeatAt = started

                c.poller.tick()
                c.engine.materialize()
                c.engine.due()
                val sent = c.deliverer.dispatchPending()
                c.scheduler.armAt(c.engine.nextDueAt())

                if (started - lastCalendar > CALENDAR_INTERVAL_MS) {
                    runCatching { c.calendar.sync() }
                        .onFailure { LogBus.post("日历同步失败: ${it.message}") }
                    lastCalendar = started
                }
                if (started - lastMaintenance > MAINTENANCE_INTERVAL_MS) {
                    runCatching { c.seen.prune(now = started) }
                    runCatching { c.alerts.prune() }
                    runCatching { c.tasks.pruneObsolete(started) }
                    runCatching { c.articles.prune(started) }
                    lastMaintenance = started
                }

                c.settings.heartbeatAt = c.clock.now()
                update(summary(sent))
            } catch (t: Throwable) {
                LogBus.post("中枢循环异常: ${t.javaClass.simpleName}: ${t.message}")
            }
            val elapsed = c.clock.now() - started
            sleep((TICK_MS - elapsed).coerceAtLeast(1_000L))
        }
        update("已停止")
    }

    private fun summary(sent: Int): String {
        val now = c.clock.now()
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val stats = c.tasks.statsBetween(dayStart, dayEnd)
        val next = c.engine.nextDueAt()
        val nextText = if (next == null) "无待办" else {
            val t = java.time.Instant.ofEpochMilli(next).atZone(zone)
            "下次 ${"%02d:%02d".format(t.hour, t.minute)}"
        }
        val head = if (sent > 0) "刚推送 $sent 条 · " else ""
        return "${head}今日 ${stats.done}/${stats.total} · $nextText"
    }

    private fun update(text: String) {
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(FOREGROUND_ID, Notifier.serviceNotification(this, text))
        }
    }

    private fun sleep(ms: Long) {
        try {
            var left = ms
            while (left > 0 && !stopRequested.get()) {
                val chunk = minOf(left, 1_000L)
                Thread.sleep(chunk)
                left -= chunk
            }
        } catch (_: InterruptedException) {
        }
    }

    /** 前台服务仍可能被 Doze 掐网络，短时持锁并在每个 tick 续期。 */
    private fun keepAwake() {
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchHub:hub")
                .apply {
                    setReferenceCounted(false)
                    acquire(WAKE_TIMEOUT_MS)
                }
        } else if (wake?.isHeld != true) {
            wake?.acquire(WAKE_TIMEOUT_MS)
        }
    }

    private fun releaseWake() {
        runCatching { if (wake?.isHeld == true) wake?.release() }
        wake = null
    }

    override fun onDestroy() {
        stopRequested.set(true)
        thread?.interrupt()
        releaseWake()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "dev.watchhub.app.STOP"
        private const val FOREGROUND_ID = 42
        private const val TICK_MS = 20_000L
        private const val WAKE_TIMEOUT_MS = 10 * 60_000L
        private const val MAINTENANCE_INTERVAL_MS = 3600_000L
        private const val CALENDAR_INTERVAL_MS = 15 * 60_000L

        fun start(ctx: Context) {
            val intent = Intent(ctx, HubService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(ctx, intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, HubService::class.java))
        }
    }
}
