package dev.watchhub.app.core

import dev.watchhub.app.core.model.HealthRule
import dev.watchhub.app.core.model.MetricSample
import dev.watchhub.app.core.model.MetricType
import dev.watchhub.app.core.model.RuleOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 期望值来自 SPEC 2026-10-25-watchhub-2.5.0 §8 手工算例：
 * 窗口内（最近 sustained 分钟）≥2 个样本且全部满足阈值才告警；
 * 最新样本超过 15 分钟视为过期不评估；命中后冷却期内不重复。
 */
class HealthRuleEngineTest {

    private val now: Long =
        LocalDateTime.of(2026, 10, 25, 10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val hrRule = HealthRule(
        name = "心率过高",
        metric = MetricType.HEART_RATE,
        op = RuleOp.ABOVE,
        threshold = 100.0,
        sustainedMin = 30,
        cooldownMin = 60,
    )

    private fun hr(value: Double, minutesAgo: Long) =
        MetricSample(MetricType.HEART_RATE, value, now - minutesAgo * 60_000)

    private fun evaluate(
        rule: HealthRule = hrRule,
        samples: List<MetricSample>,
        lastAlertAt: Long = 0,
    ) = HealthRuleEngine.evaluate(rule, samples, now, lastAlertAt)

    @Test
    fun sustainedAboveHits() {
        val d = evaluate(samples = listOf(hr(112.0, 28), hr(105.0, 15), hr(110.0, 3)))
        assertEquals(HealthRuleEngine.Decision.Hit, d)
    }

    @Test
    fun windowBoundarySampleIncluded() {
        // 恰好落在窗口边界（now-30m）的样本算进窗口
        val d = evaluate(samples = listOf(hr(101.0, 30), hr(103.0, 0)))
        assertEquals(HealthRuleEngine.Decision.Hit, d)
    }

    @Test
    fun spikeDoesNotHit() {
        // 窗口内有一个正常值 → 不是「持续」异常
        val d = evaluate(samples = listOf(hr(95.0, 28), hr(105.0, 15), hr(110.0, 3)))
        assertEquals(HealthRuleEngine.Decision.NotHit, d)
    }

    @Test
    fun singleSampleNotEnough() {
        val d = evaluate(samples = listOf(hr(110.0, 3)))
        assertEquals(HealthRuleEngine.Decision.NotHit, d)
    }

    @Test
    fun sustainedBelowHits() {
        val spo2Rule = HealthRule(
            name = "血氧过低",
            metric = MetricType.SPO2,
            op = RuleOp.BELOW,
            threshold = 93.0,
            sustainedMin = 15,
            cooldownMin = 60,
        )
        val d = evaluate(
            rule = spo2Rule,
            samples = listOf(
                MetricSample(MetricType.SPO2, 91.0, now - 10 * 60_000),
                MetricSample(MetricType.SPO2, 90.0, now - 2 * 60_000),
            ),
        )
        assertEquals(HealthRuleEngine.Decision.Hit, d)
    }

    @Test
    fun staleDataNotEvaluated() {
        // 最新样本 20 分钟前（> 15 分钟新鲜度门槛）→ 手表可能已摘掉，不评估
        val d = evaluate(samples = listOf(hr(110.0, 40), hr(112.0, 20)))
        assertEquals(HealthRuleEngine.Decision.Stale, d)
    }

    @Test
    fun cooldownBlocks() {
        val d = evaluate(samples = listOf(hr(112.0, 28), hr(110.0, 3)), lastAlertAt = now - 30 * 60_000)
        assertEquals(HealthRuleEngine.Decision.Cooldown, d)
    }

    @Test
    fun cooldownExpires() {
        val d = evaluate(samples = listOf(hr(112.0, 28), hr(110.0, 3)), lastAlertAt = now - 61 * 60_000)
        assertEquals(HealthRuleEngine.Decision.Hit, d)
    }

    @Test
    fun alertTextContainsValues() {
        val samples = listOf(hr(112.0, 28), hr(105.0, 15), hr(110.0, 3))
        val latest = samples.maxBy { it.at }
        val title = HealthRuleEngine.alertTitle(hrRule, latest)
        val body = HealthRuleEngine.alertBody(hrRule)
        assertTrue("标题应含指标与最新值: $title", title.contains("心率") && title.contains("110"))
        assertTrue("正文应含持续时长与阈值: $body", body.contains("30 分钟") && body.contains("100"))
        assertTrue("标题应表明方向: $title", title.contains("偏高"))
    }

    @Test
    fun belowAlertTextSaysLow() {
        val rule = HealthRule(
            name = "血氧过低",
            metric = MetricType.SPO2,
            op = RuleOp.BELOW,
            threshold = 93.0,
            sustainedMin = 15,
        )
        assertTrue(HealthRuleEngine.alertTitle(rule, MetricSample(MetricType.SPO2, 90.0, now)).contains("偏低"))
        assertTrue(HealthRuleEngine.alertBody(rule).contains("低于"))
    }

    @Test
    fun formatValueTrimsDecimal() {
        assertEquals("112", HealthRuleEngine.formatValue(112.0))
        assertEquals("98.5", HealthRuleEngine.formatValue(98.5))
    }
}
