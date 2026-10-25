package dev.watchhub.app.core

import dev.watchhub.app.core.model.HealthRule
import dev.watchhub.app.core.model.MetricSample
import dev.watchhub.app.core.model.RuleOp
import java.util.Locale

/**
 * 健康规则的判定（纯函数，无 Android 依赖）。
 *
 * 语义：窗口（最近 sustained 分钟）内 ≥[MIN_SAMPLES] 个样本**全部**越过阈值才算「持续异常」；
 * 最新样本超过 [FRESH_MS] 视为数据过期（手表可能已摘掉），不评估——
 * 宁可漏报不可误报，尤其「低于阈值」类规则拿旧值判断会误报。
 */
object HealthRuleEngine {

    /** 最新样本的新鲜度门槛：采样间隔 5 分钟，容忍一次采样失败。 */
    const val FRESH_MS = 15 * 60_000L

    /** 窗口内最少样本数，单次尖峰不构成「持续」。 */
    const val MIN_SAMPLES = 2

    sealed class Decision {
        object Hit : Decision()
        object NotHit : Decision()
        object Cooldown : Decision()
        object Stale : Decision()
    }

    fun evaluate(rule: HealthRule, samples: List<MetricSample>, now: Long, lastAlertAt: Long): Decision {
        val mine = samples.filter { it.type == rule.metric }.sortedBy { it.at }
        val newest = mine.lastOrNull() ?: return Decision.Stale
        if (now - newest.at > FRESH_MS) return Decision.Stale
        if (lastAlertAt > 0 && now - lastAlertAt < rule.cooldownMin * 60_000L) return Decision.Cooldown
        val windowStart = now - rule.sustainedMin * 60_000L
        val inWindow = mine.filter { it.at >= windowStart && it.at <= now }
        if (inWindow.size < MIN_SAMPLES) return Decision.NotHit
        return if (inWindow.all { satisfies(rule, it) }) Decision.Hit else Decision.NotHit
    }

    fun satisfies(rule: HealthRule, s: MetricSample): Boolean =
        if (rule.op == RuleOp.ABOVE) s.value > rule.threshold else s.value < rule.threshold

    fun alertTitle(rule: HealthRule, latest: MetricSample): String =
        "${rule.metric.label}${if (rule.op == RuleOp.ABOVE) "偏高" else "偏低"} ${formatValue(latest.value)}"

    fun alertBody(rule: HealthRule): String {
        val direction = if (rule.op == RuleOp.ABOVE) "高于" else "低于"
        return "最近 ${rule.sustainedMin} 分钟${rule.metric.label}持续${direction} " +
            "${formatValue(rule.threshold)}${rule.metric.unit}，注意休息"
    }

    /** 展示用：整数不带小数点（心率 110 而不是 110.0）。 */
    fun formatValue(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(Locale.ROOT, "%.1f", v)
}
