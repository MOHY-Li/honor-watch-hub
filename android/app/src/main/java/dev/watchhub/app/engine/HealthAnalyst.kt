package dev.watchhub.app.engine

import dev.watchhub.app.core.Clock
import dev.watchhub.app.core.HealthRuleEngine
import dev.watchhub.app.core.LogBus
import dev.watchhub.app.core.Text
import dev.watchhub.app.core.model.Alert
import dev.watchhub.app.core.model.AlertKind
import dev.watchhub.app.core.model.HealthReport
import dev.watchhub.app.core.model.MetricSample
import dev.watchhub.app.core.model.MetricType
import dev.watchhub.app.data.AlertStore
import dev.watchhub.app.data.HealthReportStore
import dev.watchhub.app.data.MetricStore
import dev.watchhub.app.data.SettingsStore
import dev.watchhub.app.source.DeepSeekClient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 单指标在窗口内的聚合统计（AI 分析只上传这些，不上传原始样本）。 */
data class MetricStat(
    val type: MetricType,
    val count: Int,
    val min: Double,
    val max: Double,
    val avg: Double,
    val latest: Double,
    val latestAt: Long,
)

/**
 * 健康分析：聚合最近 7 天指标 → DeepSeek 生成「整体情况 + 建议」报告 → 落库存档。
 *
 * 隐私：只上传聚合统计与规则触发次数；报告全文只存本地。
 * 网络失败只影响分析本身（与 RSS 抓取同类），不影响提醒/告警等核心能力。
 */
class HealthAnalyst(
    private val metrics: MetricStore,
    private val alerts: AlertStore,
    private val reports: HealthReportStore,
    private val settings: SettingsStore,
    private val clock: Clock,
) {

    /** 阻塞网络（最长约 2 分钟），必须在后台线程调用。 */
    fun analyze(now: Long = clock.now()): Result<HealthReport> = runCatching {
        val key = settings.aiApiKey
        require(key.isNotEmpty()) { "还没有配置 DeepSeek API Key（设置 → AI 分析）" }
        val since = now - WINDOW_DAYS * 24 * 3600_000L
        val stats = summarize(metrics.since(since))
        val ruleHits = alerts.countSince(AlertKind.HEALTH.name, since)
        val content = DeepSeekClient.chat(key, SYSTEM_PROMPT, buildPrompt(stats, ruleHits, WINDOW_DAYS, now))
            .takeIf { it.isNotBlank() } ?: error("模型返回了空内容")
        val report = HealthReport(
            createdAt = now,
            content = content,
            windowDays = WINDOW_DAYS,
            model = DeepSeekClient.MODEL,
        )
        reports.insert(report)
        reports.prune()
        settings.lastAiAnalysisAt = now
        report
    }

    /**
     * 每周自动分析（服务维护周期调用）。条件不满足直接返回；
     * 分析在独立线程跑，绝不阻塞心跳线程。
     */
    fun maybeAutoAnalyze(now: Long = clock.now()) {
        if (!settings.aiAutoWeekly) return
        if (settings.aiApiKey.isEmpty()) return
        if (now - settings.lastAiAnalysisAt < WEEK_MS) return
        Thread({
            analyze(now)
                .onSuccess { report ->
                    notifyDone(report)
                    LogBus.post("健康周报已生成")
                }
                .onFailure { LogBus.post("健康周报分析失败: ${it.message}") }
        }, "watchhub-ai-weekly").start()
    }

    private fun notifyDone(report: HealthReport) {
        alerts.insert(
            Alert(
                title = "健康周报已生成",
                body = Text.body(report.content.lineSequence().firstOrNull().orEmpty()),
                key = "health_report:${report.createdAt}",
                kind = AlertKind.HEALTH,
                source = "ai_analysis",
                at = report.createdAt,
            ),
        )
    }

    companion object {
        const val WINDOW_DAYS = 7
        const val WEEK_MS = 7 * 24 * 3600_000L
        const val MIN_SAMPLES_FOR_CONFIDENCE = 5

        val SYSTEM_PROMPT = """
            你是可穿戴设备健康数据分析助手。基于用户提供的身体指标统计，输出两段报告：
            【整体情况】概括各指标近期的水平与波动；【建议】给出 2-3 条具体、克制的改善建议。
            要求：简体中文；合计不超过 400 字；不做疾病诊断、不制造恐慌；
            某项数据不足时直说，不要编造。
        """.trimIndent()

        /** 纯函数：按指标聚合窗口内样本。 */
        fun summarize(samples: List<MetricSample>): Map<MetricType, MetricStat> =
            samples.groupBy { it.type }.mapValues { (type, list) ->
                val latest = list.maxBy { it.at }
                MetricStat(
                    type = type,
                    count = list.size,
                    min = list.minOf { it.value },
                    max = list.maxOf { it.value },
                    avg = list.sumOf { it.value } / list.size,
                    latest = latest.value,
                    latestAt = latest.at,
                )
            }

        /** 纯函数：把统计变成给模型的用户消息。 */
        fun buildPrompt(
            stats: Map<MetricType, MetricStat>,
            ruleHits: Int,
            windowDays: Int,
            now: Long,
        ): String = buildString {
            append("以下是用户最近 $windowDays 天的可穿戴设备指标统计")
            append("（截至 ")
            append(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).format(MM_DD_HH_MM))
            append("）。\n")
            if (stats.isEmpty()) {
                append("（没有任何指标样本——设备可能刚接入，请在报告里说明数据缺失。）\n")
            }
            for (type in MetricType.entries) {
                val s = stats[type] ?: continue
                val note = if (s.count < MIN_SAMPLES_FOR_CONFIDENCE) "，样本偏少，数据不足仅供参考" else ""
                append(
                    "- ${type.label}(${type.unit})：样本 ${s.count} 条，" +
                        "最新 ${HealthRuleEngine.formatValue(s.latest)}，" +
                        "最低 ${HealthRuleEngine.formatValue(s.min)}，" +
                        "最高 ${HealthRuleEngine.formatValue(s.max)}，" +
                        "平均 ${HealthRuleEngine.formatValue(s.avg)}$note\n",
                )
            }
            append("最近 $windowDays 天健康规则触发 $ruleHits 次。\n")
            append("请按系统要求输出报告。")
        }

        private val MM_DD_HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    }
}
