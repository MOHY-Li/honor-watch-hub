package dev.watchhub.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

/**
 * 常驻前台服务：维护一条到 WatchHub 的 SSE 长连接。
 *
 * 生命周期：
 *  - START_STICKY，被系统杀掉后会重启
 *  - 指数退避重连：1s, 2s, 4s … 上限 60s，±20% jitter（避免多设备同时打服务端）
 *  - 连续失败 5 次后仍继续重连，但把退避压到 30s 固定，并写日志提示用户检查
 */
class SseService : Service() {

    private lateinit var cfg: Config
    private var client: SseClient? = null
    private var thread: Thread? = null
    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var connected = false

    override fun onCreate() {
        super.onCreate()
        cfg = Config(this)
        Notifier.ensureChannels(this)
        startForeground(FOREGROUND_ID, Notifier.serviceNotification(this, "正在连接…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested.set(true)
            client?.close()
            stopSelf()
            return START_NOT_STICKY
        }
        if (thread == null) {
            stopRequested.set(false)
            thread = Thread({ runLoop() }, "watchhub-sse").apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    private fun runLoop() {
        var attempt = 0
        while (!stopRequested.get()) {
            if (cfg.baseUrl.isBlank()) {
                updateNotification("请先在 App 里填写服务器地址")
                sleepInterruptible(5_000)
                continue
            }
            attempt++
            val lastId = cfg.lastEventId
            val c = SseClient(
                cfg = cfg,
                onEvent = { event, id, data -> handleEvent(event, id, data) },
                onConnected = {
                    connected = true
                    attempt = 0
                    updateNotification("已连接 · 等待推送")
                    log("SSE connected")
                },
                onDisconnected = { err ->
                    connected = false
                    log("SSE disconnected: ${err?.message ?: "closed"}")
                },
            )
            client = c
            holdWakeLockWhileConnecting()
            c.connect(lastId)
            client = null
            if (stopRequested.get()) break

            val backoff = if (attempt > 5) 30_000L
            else min(60_000L, (1_000L shl min(attempt - 1, 6)))
            val jitter = (backoff * 0.2 * (Random.nextDouble() * 2 - 1)).toLong()
            val wait = (backoff + jitter).coerceAtLeast(1_000L)
            updateNotification("已断开 · ${wait / 1000}s 后重连")
            sleepInterruptible(wait)
        }
        updateNotification("已停止")
    }

    private fun handleEvent(event: String, sseId: Long, data: String) {
        when (event) {
            "hello" -> {
                // 服务端确认连接；把 cursor 作为兜底下限（避免重复补发）
                runCatching {
                    val cursor = JSONObject(data).optLong("cursor", 0L)
                    if (cursor > cfg.lastEventId) cfg.lastEventId = cursor
                }
                log("hello")
            }
            "notification" -> {
                // 服务端在 SSE 事件 id 和 data.id 里给了同一个值；以 data 为准
                val o = runCatching { JSONObject(data) }.getOrNull() ?: return
                var n = Notif.fromJson(o)
                // 正常情况下 data.id 存在；容错：SSE 的 id 字段可作为兜底
                if (n.id <= 0 && sseId > 0) n = n.copy(id = sseId)
                if (n.id <= 0) return
                if (cfg.isSeen(n.id)) return
                cfg.markSeen(n.id)
                if (n.id > cfg.lastEventId) cfg.lastEventId = n.id
                Notifier.show(this, n)
                log("notify #${n.id} ${n.title}")
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * 只在「建立连接/等待数据」时持有 partial wakelock，并且限时。
     * 不长期持有，避免被系统判定为耗电应用后强杀。
     */
    private fun holdWakeLockWhileConnecting() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchHub:sse").apply {
                setReferenceCounted(false)
                acquire(90_000L)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wakelock: ${t.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {}
        wakeLock = null
    }

    private fun sleepInterruptible(ms: Long) {
        releaseWakeLock()
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(FOREGROUND_ID, Notifier.serviceNotification(this, text))
        } catch (_: Throwable) {}
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        LogBus.post(msg)
    }

    override fun onDestroy() {
        stopRequested.set(true)
        client?.close()
        thread?.interrupt()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "WatchHubSse"
        const val ACTION_STOP = "dev.watchhub.app.STOP"
        private const val FOREGROUND_ID = 42

        fun start(ctx: Context) {
            val i = Intent(ctx, SseService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SseService::class.java))
        }
    }
}
