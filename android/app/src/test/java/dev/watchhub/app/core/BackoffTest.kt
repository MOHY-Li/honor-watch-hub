package dev.watchhub.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 失败退避的期望值来自 SPEC 2026-10-24-watchhub-2.4.2 §8 的手工算例：
 * fails < 3 用基础间隔；从第 3 次连续失败起每次翻倍；封顶 max(base, 6h)。
 */
class BackoffTest {

    @Test
    fun failsBelowThresholdKeepsBase() {
        assertEquals(900, Backoff.effectiveIntervalSec(900, 0))
        assertEquals(900, Backoff.effectiveIntervalSec(900, 1))
        assertEquals(900, Backoff.effectiveIntervalSec(900, 2))
    }

    @Test
    fun doublesFromThirdFailure() {
        assertEquals(1800, Backoff.effectiveIntervalSec(900, 3))
        assertEquals(3600, Backoff.effectiveIntervalSec(900, 4))
        assertEquals(7200, Backoff.effectiveIntervalSec(900, 5))
    }

    @Test
    fun cappedAtSixHours() {
        // 900 * 2^6 = 57600 > 21600
        assertEquals(21600, Backoff.effectiveIntervalSec(900, 8))
        assertEquals(21600, Backoff.effectiveIntervalSec(900, 100))
    }

    @Test
    fun capNeverSpeedsUpSlowSources() {
        // 基础间隔本来就超过 6h 的源（如每日一查），退避不应把它变快
        assertEquals(86400, Backoff.effectiveIntervalSec(86400, 10))
    }

    @Test
    fun minIntervalSourceStillBacksOff() {
        assertEquals(60, Backoff.effectiveIntervalSec(60, 2))
        assertEquals(120, Backoff.effectiveIntervalSec(60, 3))
        assertEquals(21600, Backoff.effectiveIntervalSec(60, 20))
    }

    @Test
    fun hugeBaseAndFailsDoNotOverflow() {
        val bigBase = Int.MAX_VALUE / 2
        assertEquals(bigBase, Backoff.effectiveIntervalSec(bigBase, 5000))
        assertEquals(21600, Backoff.effectiveIntervalSec(60, Int.MAX_VALUE))
    }

    @Test
    fun degenerateInputsReturnBase() {
        assertEquals(900, Backoff.effectiveIntervalSec(900, -5))
        assertEquals(0, Backoff.effectiveIntervalSec(0, 9))
    }

    @Test
    fun intervalTextUsesChineseUnits() {
        assertEquals("45 秒", Backoff.intervalText(45))
        assertEquals("1 分钟", Backoff.intervalText(60))
        assertEquals("2 分钟", Backoff.intervalText(120))
        assertEquals("1 小时", Backoff.intervalText(3600))
        assertEquals("6 小时", Backoff.intervalText(21600))
    }
}
