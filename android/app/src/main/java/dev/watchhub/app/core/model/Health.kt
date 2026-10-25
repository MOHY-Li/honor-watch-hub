package dev.watchhub.app.core.model

/**
 * 健康指标域的模型。指标来源是手机侧「荣耀运动健康」（经 Health Kit），
 * 与 FeedItem（外部世界发生的事）平行；告警复用 Alert 管道。
 */

/** 手表能测、本域支持的指标。范围用于表单校验与脏样本过滤，不是医学判断。 */
enum class MetricType(val label: String, val unit: String, val min: Double, val max: Double) {
    HEART_RATE("心率", "bpm", 30.0, 220.0),
    SPO2("血氧", "%", 70.0, 100.0),
    BP_SYS("收缩压", "mmHg", 80.0, 200.0),
    BP_DIA("舒张压", "mmHg", 50.0, 130.0),
    STRESS("压力", "", 0.0, 100.0);

    fun isValid(value: Double): Boolean = value in min..max

    companion object {
        fun parse(v: String?): MetricType? =
            entries.firstOrNull { it.name == v?.trim()?.uppercase() }
    }
}

/** 一次测量值。at 为测量/采样时刻（毫秒）。 */
data class MetricSample(val type: MetricType, val value: Double, val at: Long)

/** 阈值方向。 */
enum class RuleOp {
    ABOVE,
    BELOW;

    companion object {
        fun parse(v: String?): RuleOp = if (v?.trim()?.uppercase() == "BELOW") BELOW else ABOVE
    }
}

/**
 * 健康规则：某指标在窗口内持续越过阈值 → 告警（走 Alert → 手表）。
 *
 * - [sustainedMin]：窗口长度，「最近 N 分钟内每次测量都越界」才命中（单尖峰不报）。
 * - [cooldownMin]：命中后的静默期，防止持续异常期间刷屏。
 * - [lastAlertAt]：运行状态（落库），进程重启后冷却仍生效。
 */
data class HealthRule(
    val name: String,
    val metric: MetricType,
    val op: RuleOp,
    val threshold: Double,
    val id: Long = 0,
    val sustainedMin: Int = 15,
    val cooldownMin: Int = 60,
    val priority: Priority = Priority.NORMAL,
    val enabled: Boolean = true,
    val lastAlertAt: Long = 0,
)

/** 一次 AI 分析产出的报告（全文存本地，手机端查看）。 */
data class HealthReport(
    val createdAt: Long,
    val content: String,
    val windowDays: Int = 7,
    val model: String = "",
    val id: Long = 0,
)
