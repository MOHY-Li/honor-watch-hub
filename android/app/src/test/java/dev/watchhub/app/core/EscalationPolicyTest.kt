package dev.watchhub.app.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class EscalationPolicyTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun intervalFor_followsDefaultLadder() {
        assertEquals(20, EscalationPolicy.intervalFor(0))
        assertEquals(15, EscalationPolicy.intervalFor(1))
        assertEquals(10, EscalationPolicy.intervalFor(2))
        assertEquals(5, EscalationPolicy.intervalFor(3))
        assertEquals(1, EscalationPolicy.intervalFor(4))
    }

    @Test
    fun intervalFor_floorAtOneMinute() {
        assertEquals(1, EscalationPolicy.intervalFor(5))
        assertEquals(1, EscalationPolicy.intervalFor(99))
    }

    @Test
    fun intervalFor_customLadder() {
        assertEquals(30, EscalationPolicy.intervalFor(0, ladder = listOf(30, 10)))
        assertEquals(10, EscalationPolicy.intervalFor(1, ladder = listOf(30, 10)))
        assertEquals(10, EscalationPolicy.intervalFor(2, ladder = listOf(30, 10)))
    }

    @Test
    fun intervalFor_neverBelowOneEvenWithBadLadder() {
        assertEquals(1, EscalationPolicy.intervalFor(0, ladder = listOf(0, -5)))
        assertEquals(1, EscalationPolicy.intervalFor(3, ladder = emptyList()))
    }

    @Test
    fun nextLevel_increments() {
        assertEquals(1, EscalationPolicy.nextLevel(0))
        assertEquals(5, EscalationPolicy.nextLevel(4))
    }

    @Test
    fun resetIfNewDay_keepsLevelSameDay() {
        val start = ms(2026, 10, 19, 9, 0)
        assertEquals(3, EscalationPolicy.resetIfNewDay(3, start, ms(2026, 10, 19, 23, 59), zone))
    }

    @Test
    fun resetIfNewDay_resetsNextDay() {
        val start = ms(2026, 10, 19, 23, 50)
        assertEquals(0, EscalationPolicy.resetIfNewDay(4, start, ms(2026, 10, 20, 0, 10), zone))
    }

    @Test
    fun resetIfNewDay_zeroLevelStaysZero() {
        val start = ms(2026, 10, 19, 9, 0)
        assertEquals(0, EscalationPolicy.resetIfNewDay(0, start, ms(2026, 10, 20, 9, 0), zone))
    }

    @Test
    fun resetIfNewDay_missingChainStartKeepsLevel() {
        assertEquals(2, EscalationPolicy.resetIfNewDay(2, 0L, ms(2026, 10, 20, 9, 0), zone))
    }

    @Test
    fun baseIntervalIsTwentyFiveMinutes() {
        assertEquals(25, EscalationPolicy.BASE_INTERVAL_MIN)
    }

    @Test
    fun ladderForDisplay_isReadable() {
        assertEquals("20/15/10/5/1 分钟", EscalationPolicy.ladderForDisplay())
    }
}
