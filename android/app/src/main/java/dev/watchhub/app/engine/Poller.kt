package dev.watchhub.app.engine

import dev.watchhub.app.core.Backoff
import dev.watchhub.app.core.Clock
import dev.watchhub.app.core.LogBus
import dev.watchhub.app.core.model.Alert
import dev.watchhub.app.core.model.AlertKind
import dev.watchhub.app.core.model.FeedItem
import dev.watchhub.app.data.AlertStore
import dev.watchhub.app.data.ArticleStore
import dev.watchhub.app.data.SeenStore
import dev.watchhub.app.data.SourceStore
import dev.watchhub.app.data.SourceState
import dev.watchhub.app.source.Source
import dev.watchhub.app.source.SourceDef
import dev.watchhub.app.source.SourceFactory

/**
 * 源轮询器。名字里不再有 engine —— 它只负责「拉取 → 去重 → 生成待送达 alert」，
 * 发通知是 delivery 层的事。
 *
 * 每个源独立倒计时，避免 30s 的源拖累 30min 的源。
 */
class Poller(
    private val sources: SourceStore,
    private val alerts: AlertStore,
    private val articles: ArticleStore,
    private val seen: SeenStore,
    private val clock: Clock,
) {

    private val lastRun = HashMap<String, Long>()
    private val inFlight = HashMap<String, Long>()

    /** 跑一轮。返回本轮新生成的 alert 数。 */
    fun tick(): Int {
        var produced = 0
        val now = clock.now()
        for (def in sources.all()) {
            if (!def.enabled) continue
            if (now < (lastRun[def.name] ?: 0L) + effectiveIntervalMs(def)) continue

            // 同一个源不并发检查；但卡住太久（网络挂住）要放行重试，否则该源永久瘫痪
            val startedAt = inFlight[def.name]
            if (startedAt != null) {
                val stuck = now - startedAt
                if (stuck < STUCK_TIMEOUT_MS) continue
                log("[${def.name}] 上次检查卡住 ${stuck / 1000}s，强制重试")
            }

            inFlight[def.name] = now
            lastRun[def.name] = now
            try {
                produced += checkOne(def, now)
            } finally {
                inFlight.remove(def.name)
            }
        }
        return produced
    }

    /** 是否有到期的源需要联网检查（服务用它决定要不要持 wake lock）。 */
    fun hasDueWork(now: Long): Boolean = sources.all().any { def ->
        def.enabled && now >= (lastRun[def.name] ?: 0L) + effectiveIntervalMs(def)
    }

    /**
     * 该源下一次到期检查的间隔：连续失败达到阈值后指数退避（见 [Backoff]），
     * 成功一次自动恢复。手动 [checkNow] 不经过这里。
     */
    private fun effectiveIntervalMs(def: SourceDef): Long =
        Backoff.effectiveIntervalSec(def.intervalSec, sources.stateOf(def.name).fails) * 1000L

    /** 手动触发某个源（忽略倒计时）。 */
    fun checkNow(name: String): Int {
        val def = sources.get(name) ?: return 0
        lastRun[name] = 0L
        return checkOne(def, clock.now())
    }

    private fun checkOne(def: SourceDef, now: Long): Int {
        val state = sources.stateOf(def.name)
        return try {
            val source = SourceFactory.create(def)
            val items = source.fetch()
            // 不管推不推送，都进文章库供订阅页回看（唯一键保证幂等）
            items.forEach { articles.insertIfNew(it, now) }
            val priming = !state.seeded && !def.notifyOnFirstRun
            var produced = 0
            if (priming) {
                seen.markAllSeen(items.map { it.key }.filter { it.isNotBlank() }, now)
                log("[${def.name}] 首轮播种 ${items.size} 条，不推送")
            } else {
                items.forEach { item ->
                    if (item.key.isNotBlank() && seen.isSeen(item.key)) return@forEach
                    if (item.key.isNotBlank()) seen.markSeen(item.key, now)
                    if (alerts.insert(toAlert(item, now)) != null) produced++
                }
            }
            sources.saveState(
                state.copy(
                    lastCheck = now,
                    lastOk = now,
                    lastError = null,
                    fails = 0,
                    itemsSeen = state.itemsSeen + items.size,
                    seeded = true,
                )
            )
            if (produced > 0) log("[${def.name}] 新增 $produced 条")
            produced
        } catch (t: Throwable) {
            val msg = "${t.javaClass.simpleName}: ${t.message}".take(200)
            sources.saveState(state.copy(lastCheck = now, lastError = msg, fails = state.fails + 1))
            log("[${def.name}] 失败(${state.fails + 1}): $msg")
            0
        }
    }

    private fun toAlert(item: FeedItem, now: Long) = Alert(
        title = item.title,
        key = item.key,
        kind = AlertKind.FEED,
        source = item.source,
        body = item.body,
        detail = item.detail,
        url = item.url,
        priority = item.priority,
        tags = item.tags,
        at = now,
    )

    private fun log(s: String) = LogBus.post(s)

    companion object {
        /** 单源检查的最长容忍时间，超过视为卡死。 */
        const val STUCK_TIMEOUT_MS = 3 * 60 * 1000L
    }
}
