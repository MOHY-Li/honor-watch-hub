package dev.watchhub.app.hub

import android.content.Context
import android.util.Log
import dev.watchhub.app.LogBus
import dev.watchhub.app.Notif
import dev.watchhub.app.Notifier

/**
 * 本地引擎：定时检查所有源，把新条目变成系统通知。
 *
 * 这是把原来 Python 服务端搬进 APK 的核心。跑在 SseService 的前台进程里，
 * 所以有前台优先级 + 不会被 Doze 冻结网络。
 *
 * 每个源独立倒计时：检查完一个源后把它自己的 nextCheck 往后推 interval，
 * 而不是全局 sleep interval（否则 30s 的 cron 会拖累 30min 的 RSS）。
 */
class Engine private constructor(private val ctx: Context) {

    private val repo = SourceRepo(ctx)
    private val store = Store(ctx)
    private val lastRun = HashMap<String, Long>()
    /** 源名 -> 开始检查的时间戳。用于发现「卡住不返回」的源。 */
    private val inFlight = HashMap<String, Long>()

    @Volatile
    var running = false
        private set

    fun start() {
        running = true
    }

    fun stop() {
        running = false
    }

    /** 引擎跑一圈：所有到期的源都检查一遍。返回本轮新通知数。 */
    fun tick(): Int {
        if (!running) return 0
        var produced = 0
        val now = System.currentTimeMillis()
        for (src in repo.load()) {
            if (!src.enabled) continue
            val next = lastRun[src.name] ?: 0L
            // cron 必须每分钟碰一次（否则会漏掉整分钟）
            val intervalMs = if (src is CronSource) 60_000L else src.intervalSec * 1000L
            if (now < next + intervalMs) continue

            // 重入保护：同一源不并发检查。
            // 但如果上一次已经卡了很久（网络挂住、DNS 不返回），
            // 就放行重试并记一条警告——否则这个源会永久瘫痪。
            val startedAt = inFlight[src.name]
            if (startedAt != null) {
                val stuckMs = now - startedAt
                if (stuckMs < STUCK_TIMEOUT_MS) continue
                log("[${src.name}] 上次检查已卡住 ${stuckMs / 1000}s，强制重试")
            }

            inFlight[src.name] = now
            lastRun[src.name] = now
            try {
                produced += checkOne(src)
            } finally {
                inFlight.remove(src.name)
            }
        }
        return produced
    }

    private fun checkOne(src: Source): Int {
        val st = store.stateOf(src.name)
        val started = System.currentTimeMillis()
        return try {
            val items = src.fetch()
            // 首轮播种：只记已见，不推送历史（cron 除外）
            val priming = !st.seeded && !src.notifyOnFirstRun
            var pushed = 0
            for (item in items) {
                if (priming) {
                    store.markSeen(item.key)
                    continue
                }
                val id = store.insertIfNew(item) ?: continue
                pushed++
                Notifier.show(ctx, toNotif(id, item))
            }
            if (priming) log("[${src.name}] 首轮播种 ${items.size} 条，不推送")
            store.saveState(
                src.name,
                Store.JSONObj(
                    lastCheck = started,
                    lastOk = System.currentTimeMillis(),
                    lastError = null,
                    itemsSeen = st.itemsSeen + items.size,
                    newLastRun = pushed,
                    seeded = true,
                )
            )
            if (pushed > 0) log("[${src.name}] 推送 $pushed 条")
            pushed
        } catch (t: Throwable) {
            store.saveState(
                src.name,
                st.copy(
                    lastCheck = started,
                    lastError = "${t.javaClass.simpleName}: ${t.message}".take(200),
                    newLastRun = 0,
                )
            )
            log("[${src.name}] 失败: ${t.message}")
            Log.w(TAG, "check ${src.name}", t)
            0
        }
    }

    fun statuses(): List<SourceStatus> {
        return repo.load().map { src ->
            val st = store.stateOf(src.name)
            SourceStatus(
                name = src.name,
                kind = src.raw.optString("kind", "rss"),
                enabled = src.enabled,
                intervalSec = src.intervalSec,
                lastCheck = st.lastCheck,
                lastOk = st.lastOk,
                lastError = st.lastError,
                itemsSeen = st.itemsSeen,
                newLastRun = st.newLastRun,
            )
        }
    }

    /** 手动触发某个源（忽略倒计时）。 */
    fun checkNow(name: String): Int {
        val src = repo.load().firstOrNull { it.name == name } ?: return 0
        lastRun[name] = 0L
        return checkOne(src)
    }

    fun resetState() {
        lastRun.clear()
    }

    private fun toNotif(id: Long, item: Item) = Notif(
        id = id,
        ts = "",
        title = item.title,
        body = item.body,
        detail = item.detail,
        priority = item.priority,
        tags = item.tags,
        url = item.url,
        source = item.source,
    )

    private fun log(s: String) {
        Log.i(TAG, s)
        LogBus.post(s)
    }

    companion object {
        private const val TAG = "WatchHubEngine"

        /** 单源检查的最长容忍时间，超过视为卡死（FlClash 的 updatingStaleTimeout 同理）。 */
        private const val STUCK_TIMEOUT_MS = 3 * 60 * 1000L

        /**
         * 进程级单例。
         *
         * 为什么必须是单例：Engine 持有 lastRun / busy 这些「谁上次什么时候跑的」状态。
         * 如果 UI 每次 refresh 都 new 一个，新实例的 lastRun 是空的，
         * 会让「立即检查」把倒计时清零，紧接着后台 tick 又立刻重查一遍。
         * FlClash 用 Riverpod 的 @Riverpod(keepAlive: true) 解决同一问题。
         */
        @Volatile
        private var instance: Engine? = null

        fun get(ctx: Context): Engine =
            instance ?: synchronized(this) {
                instance ?: Engine(ctx.applicationContext).also { instance = it }
            }
    }
}


