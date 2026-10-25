package dev.watchhub.app.engine

import dev.watchhub.app.core.Clock
import dev.watchhub.app.core.HealthRuleEngine
import dev.watchhub.app.core.LogBus
import dev.watchhub.app.core.Text
import dev.watchhub.app.core.model.Alert
import dev.watchhub.app.core.model.AlertKind
import dev.watchhub.app.core.model.MetricSample
import dev.watchhub.app.data.AlertStore
import dev.watchhub.app.data.HealthRuleStore
import dev.watchhub.app.data.MetricStore

/**
 * 健康指标的读取端。
 *
 * 真实实现是荣耀 Health Kit（开发者资格过审后接入，形态对齐官方 demo
 * `com.hihonor.mcs.fitness.health`：按指标读最近测量值）。在那之前用
 * [UnavailableHealthReader]，首页/体检页会明确显示「未接入」。
 */
interface HealthMetricsReader {
    /** 端侧服务是否可用（SDK 初始化成功 + 用户已授权）。 */
    fun isAvailable(): Boolean

    /** 不可用时给用户看的说明。 */
    fun unavailableReason(): String

    /** 每种指标读最新一次测量。实现侧应丢弃脏值（超合理范围的样本）。 */
    fun readLatest(): List<MetricSample>
}

/** 资格过审前的默认实现：始终不可用、无数据。 */
object UnavailableHealthReader : HealthMetricsReader {
    override fun isAvailable(): Boolean = false
    override fun unavailableReason(): String = "Health Kit 未接入（荣耀开发者平台审核中）"
    override fun readLatest(): List<MetricSample> = emptyList()
}

/**
 * 健康引擎：周期采样（5 分钟节流）→ 落库 → 逐条评估启用的规则 → 命中即生成 Alert。
 *
 * 判定语义全部在 [HealthRuleEngine]（纯函数、有单测）；这里只做编排与落库。
 * 告警 key 绑定「规则 + 最新样本时间」，同一批样本只会落一条，重启补发不重复。
 */
class HealthEngine(
    private val reader: HealthMetricsReader,
    private val metrics: MetricStore,
    private val rules: HealthRuleStore,
    private val alerts: AlertStore,
    private val clock: Clock,
) {

    private var lastSampleAt = 0L

    data class Status(val available: Boolean, val reason: String, val lastSampleAt: Long)

    fun tick(now: Long = clock.now()) {
        if (reader.isAvailable() && now - lastSampleAt >= SAMPLE_INTERVAL_MS) {
            try {
                val samples = reader.readLatest()
                metrics.insertAll(samples)
                lastSampleAt = now
            } catch (t: Throwable) {
                // 采样失败也节流：未授权/服务不在时每 20s 重试只会刷日志
                lastSampleAt = now
                LogBus.post("健康指标采样失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        evaluateRules(now)
    }

    private fun evaluateRules(now: Long) {
        for (rule in rules.all()) {
            if (!rule.enabled) continue
            // 窗口多取 FRESH_MS，让引擎能看到「最新样本是否已过期」
            val since = now - (rule.sustainedMin * 60_000L + HealthRuleEngine.FRESH_MS)
            val samples = metrics.window(rule.metric, since)
            val decision = HealthRuleEngine.evaluate(rule, samples, now, rule.lastAlertAt)
            if (decision != HealthRuleEngine.Decision.Hit) continue
            val latest = samples.maxBy { it.at }
            val alert = Alert(
                title = Text.title(HealthRuleEngine.alertTitle(rule, latest)),
                body = Text.body(HealthRuleEngine.alertBody(rule)),
                key = alertKey(rule.id, latest.at),
                kind = AlertKind.HEALTH,
                source = rule.name,
                priority = rule.priority,
                at = now,
            )
            // key 冲突（重启后重放同一批样本）也算已报过，同样进入冷却
            alerts.insert(alert)
            rules.updateLastAlertAt(rule.id, now)
            LogBus.post("[health] 规则「${rule.name}」触发")
        }
    }

    fun prune(now: Long = clock.now()) = metrics.prune(now - RETENTION_MS)

    fun status(): Status = Status(
        available = reader.isAvailable(),
        reason = if (reader.isAvailable()) "" else reader.unavailableReason(),
        lastSampleAt = metrics.latestEach().values.maxOfOrNull { it.at } ?: 0L,
    )

    companion object {
        const val SAMPLE_INTERVAL_MS = 5 * 60_000L
        const val RETENTION_MS = 7 * 24 * 3600_000L

        fun alertKey(ruleId: Long, sampleAt: Long) = "health:$ruleId:$sampleAt"
    }
}
