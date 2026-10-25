package dev.watchhub.app.engine

import dev.watchhub.app.core.model.MetricSample
import dev.watchhub.app.core.model.MetricType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** 期望值来自 SPEC 2026-10-25-watchhub-2.5.0 §12 A12/A13 的手工算例。 */
class HealthAnalystTest {

    private val t0: Long =
        LocalDateTime.of(2026, 10, 25, 10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun hr(v: Double, at: Long) = MetricSample(MetricType.HEART_RATE, v, at)
    private fun spo2(v: Double, at: Long) = MetricSample(MetricType.SPO2, v, at)

    @Test
    fun summarizeStats() {
        val stats = HealthAnalyst.summarize(
            listOf(
                hr(60.0, t0),
                hr(80.0, t0 + 60_000),
                hr(100.0, t0 + 120_000),
                spo2(97.0, t0),
                spo2(95.0, t0 + 60_000),
            ),
        )
        assertEquals(setOf(MetricType.HEART_RATE, MetricType.SPO2), stats.keys)
        val h = stats.getValue(MetricType.HEART_RATE)
        assertEquals(3, h.count)
        assertEquals(60.0, h.min, 0.0)
        assertEquals(100.0, h.max, 0.0)
        assertEquals(80.0, h.avg, 0.0)
        assertEquals(100.0, h.latest, 0.0)
        val s = stats.getValue(MetricType.SPO2)
        assertEquals(96.0, s.avg, 0.0)
        assertEquals(95.0, s.min, 0.0)
    }

    @Test
    fun summarizeEmpty() {
        assertTrue(HealthAnalyst.summarize(emptyList()).isEmpty())
    }

    @Test
    fun promptContainsStatsAndInsufficiency() {
        val stats = HealthAnalyst.summarize(
            listOf(
                hr(60.0, t0),
                hr(80.0, t0 + 60_000),
                hr(100.0, t0 + 120_000),
                spo2(97.0, t0),
                spo2(95.0, t0 + 60_000),
            ),
        )
        val prompt = HealthAnalyst.buildPrompt(stats, ruleHits = 2, windowDays = 7, now = t0)
        assertTrue("应含指标名: $prompt", prompt.contains("心率"))
        assertTrue("应含均值 80: $prompt", prompt.contains("80"))
        assertTrue("应含规则触发次数: $prompt", prompt.contains("2 次"))
        assertTrue("血氧样本不足应标注: $prompt", prompt.contains("数据不足"))
        assertTrue("应含窗口天数: $prompt", prompt.contains("7 天"))
    }
}
