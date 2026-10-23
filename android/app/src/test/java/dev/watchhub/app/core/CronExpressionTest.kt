package dev.watchhub.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class CronExpressionTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        ZonedDateTime.of(LocalDate.of(y, m, d), LocalTime.of(h, min), zone).toInstant().toEpochMilli()

    // ---------- parse ----------

    @Test
    fun parse_rejectsWrongFieldCount() {
        assertNull(CronExpression.parse(""))
        assertNull(CronExpression.parse("* * * *"))
        assertNull(CronExpression.parse("* * * * * *"))
    }

    @Test
    fun parse_rejectsOutOfRange() {
        assertNull(CronExpression.parse("60 * * * *"))
        assertNull(CronExpression.parse("* 24 * * *"))
        assertNull(CronExpression.parse("* * 0 * *"))
        assertNull(CronExpression.parse("* * * 13 *"))
        assertNull(CronExpression.parse("* * * * 8"))
        assertNull(CronExpression.parse("a b c d e"))
    }

    @Test
    fun parse_acceptsTokenForms() {
        assertTrue(CronExpression.parse("* * * * *") != null)
        assertTrue(CronExpression.parse("*/25 * * * *") != null)
        assertTrue(CronExpression.parse("0,30 9-11 * * *") != null)
        assertTrue(CronExpression.parse("0 9 1-15/2 * 1-5") != null)
        assertTrue(CronExpression.parse("0 0 * * 7") != null)
    }

    // ---------- nextAfter ----------

    @Test
    fun nextAfter_every25Minutes() {
        val c = CronExpression.parse("*/25 * * * *")!!
        assertEquals(at(2026, 10, 19, 10, 25), c.nextAfter(at(2026, 10, 19, 10, 0), zone))
        assertEquals(at(2026, 10, 19, 10, 50), c.nextAfter(at(2026, 10, 19, 10, 26), zone))
        // 10:50 之后下一次是 11:00（cron 按整点对齐，不是固定间隔）
        assertEquals(at(2026, 10, 19, 11, 0), c.nextAfter(at(2026, 10, 19, 10, 50), zone))
    }

    @Test
    fun nextAfter_dailyAtNine() {
        val c = CronExpression.parse("0 9 * * *")!!
        assertEquals(at(2026, 10, 19, 9, 0), c.nextAfter(at(2026, 10, 19, 8, 0), zone))
        assertEquals(at(2026, 10, 20, 9, 0), c.nextAfter(at(2026, 10, 19, 9, 30), zone))
    }

    @Test
    fun nextAfter_weekdaysSkipsWeekend() {
        // 2026-10-23 是周五，下一次工作日 9 点是下周一
        val c = CronExpression.parse("0 9 * * 1-5")!!
        assertEquals(at(2026, 10, 26, 9, 0), c.nextAfter(at(2026, 10, 23, 15, 0), zone))
    }

    @Test
    fun nextAfter_sundayAsZeroOrSeven() {
        // 2026-10-25 是周日
        val sunday = at(2026, 10, 25, 9, 0)
        assertEquals(sunday, CronExpression.parse("0 9 * * 0")!!.nextAfter(at(2026, 10, 24, 12, 0), zone))
        assertEquals(sunday, CronExpression.parse("0 9 * * 7")!!.nextAfter(at(2026, 10, 24, 12, 0), zone))
    }

    @Test
    fun nextAfter_domOrDow() {
        // 1 号或周一，取更早的那个；2026-10-19 是周一
        val c = CronExpression.parse("0 9 1 * 1")!!
        assertEquals(at(2026, 10, 19, 9, 0), c.nextAfter(at(2026, 10, 18, 12, 0), zone))
    }

    @Test
    fun nextAfter_listsAndRanges() {
        val c = CronExpression.parse("0,30 9-11 * * *")!!
        assertEquals(at(2026, 10, 19, 9, 0), c.nextAfter(at(2026, 10, 19, 8, 59), zone))
        assertEquals(at(2026, 10, 19, 9, 30), c.nextAfter(at(2026, 10, 19, 9, 0), zone))
        assertEquals(at(2026, 10, 19, 10, 0), c.nextAfter(at(2026, 10, 19, 9, 30), zone))
        assertEquals(at(2026, 10, 20, 9, 0), c.nextAfter(at(2026, 10, 19, 11, 30), zone))
    }

    @Test
    fun nextAfter_strictlyAfterNow() {
        val c = CronExpression.parse("0 9 * * *")!!
        assertEquals(at(2026, 10, 20, 9, 0), c.nextAfter(at(2026, 10, 19, 9, 0), zone))
    }

    @Test
    fun nextAfter_impossibleDateReturnsNull() {
        assertNull(CronExpression.parse("0 0 30 2 *")!!.nextAfter(at(2026, 1, 1, 0, 0), zone))
    }

    // ---------- matches ----------

    @Test
    fun matches_singlePoint() {
        val c = CronExpression.parse("0 9 * * 1-5")!!
        assertTrue(c.matches(ZonedDateTime.of(2026, 10, 19, 9, 0, 0, 0, zone)))
        assertFalse(c.matches(ZonedDateTime.of(2026, 10, 19, 9, 1, 0, 0, zone)))
        assertFalse(c.matches(ZonedDateTime.of(2026, 10, 25, 9, 0, 0, 0, zone))) // 周日
    }
}
