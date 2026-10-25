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
import dev.watchhub.app.core.ServicePolicy
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

    // 循环策略状态（ServicePolicy 的输入），只在服务线程读写
    private var lastMaterializeAt = 0L
    private var lastArmedTarget: Long? = null
    private var lastNotificationText: String? = null

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
                c.settings.heartbeatAt = started

                // 任务已提前补到 7 天后，每小时补一次足够（见 ServicePolicy 单测）
                if (ServicePolicy.shouldMaterialize(started, lastMaterializeAt)) {
                    c.engine.materialize()
                    lastMaterializeAt = c.clock.now()
                }
                c.engine.due()
                val sent = c.deliverer.dispatchPending()

                // 目标没变就不重复调 AlarmManager
                val next = c.engine.nextDueAt()
                if (ServicePolicy.shouldRearm(lastArmedTarget, next)) {
                    c.scheduler.armAt(next)
                    lastArmedTarget = next
                }

                // 升级链激活时持续保活（1 分钟级 nudge 需要 CPU 醒着）
                val escalating = c.engine.hasActiveEscalation()
                if (escalating) keepAwake(ESCALATION_WAKE_MS)

                // 只有真的要去抓源时才持锁，抓完立刻释放（升级链期间保留到本轮结束）
                if (c.poller.hasDueWork(started)) {
                    keepAwake(WAKE_ON_POLL_MS)
                    try {
                        c.poller.tick()
                    } finally {
                        if (!escalating) releaseWake()
                    }
                }

                // 健康指标：采样节流在引擎内部（5 分钟），评估是本地读，代价可忽略
                runCatching { c.health.tick(started) }
                    .onFailure { LogBus.post("健康规则处理失败: ${it.message}") }

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
                    runCatching { c.health.prune(started) }
                    // 每周自动 AI 分析：条件不满足立即返回；分析在独立线程，不阻塞心跳
                    runCatching { c.analyst.maybeAutoAnalyze(started) }
                    lastMaintenance = started
                }

                c.settings.heartbeatAt = c.clock.now()
                val text = summary(sent)
                if (ServicePolicy.shouldUpdateNotification(lastNotificationText, text)) {
                    update(text)
                    lastNotificationText = text
                }
            } catch (t: Throwable) {
                LogBus.post("中枢循环异常: ${t.javaClass.simpleName}: ${t.message}")
            }
            // 有 2 分钟内到期任务、或升级链激活时用 20s，否则 60s
            val escalatingNow = c.engine.hasActiveEscalation()
            if (!escalatingNow) releaseWake()
            val interval = ServicePolicy.tickIntervalMs(c.clock.now(), c.engine.nextDueAt(), escalatingNow)
            val elapsed = c.clock.now() - started
            sleep((interval - elapsed).coerceAtLeast(1_000L))
        }
        update("已停止")
    }

    private fun summary(sent: Int): String {
        val zone = java.time.ZoneId.systemDefault()
        val next = c.engine.nextDueAt()
        val nextText = if (next == null) "无待办" else {
            val t = java.time.Instant.ofEpochMilli(next).atZone(zone)
            "下次 ${"%02d:%02d".format(t.hour, t.minute)}"
        }
        val head = if (sent > 0) "刚推送 $sent 条 · " else ""
        return head + nextText
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

    /** 只在真正联网抓源时持锁，抓完立刻释放，避免常驻持锁。 */
    private fun keepAwake(timeoutMs: Long) {
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchHub:hub")
                .apply { setReferenceCounted(false) }
        }
        if (wake?.isHeld != true) wake?.acquire(timeoutMs)
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
        private const val WAKE_ON_POLL_MS = 5 * 60_000L
        private const val ESCALATION_WAKE_MS = 2 * 60_000L
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
