package dev.watchhub.app.core

import dev.watchhub.app.core.model.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RecurrenceTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        ZonedDateTime.of(LocalDate.of(y, m, d), LocalTime.of(h, min), zone).toInstant().toEpochMilli()

    private val weekdays = Recurrence.parse(
        listOf("09:30", "14:30"),
        listOf(0, 1, 2, 3, 4),
    )

    @Test
    fun parse_timesAndDays() {
        assertEquals(2, weekdays.times.size)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), weekdays.days.filter { it == DayOfWeek.MONDAY || it == DayOfWeek.FRIDAY }.toSet())
        assertTrue(!weekdays.daily)
    }

    @Test
    fun parse_dropsInvalidEntries() {
        val r = Recurrence.parse(listOf("25:00", "09:00", "9:5", "07:15"), listOf(7, -1, 2))
        assertEquals(listOf(LocalTime.of(7, 15), LocalTime.of(9, 0)), r.times)
        assertEquals(setOf(DayOfWeek.WEDNESDAY), r.days)
    }

    @Test
    fun parse_emptyDaysMeansDaily() {
        val r = Recurrence.parse(listOf("08:00"), emptyList())
        assertTrue(r.daily)
        assertEquals(1, r.times.size)
    }

    @Test
    fun nextAfter_sameDayBeforeFirstTime() {
        // 2026-10-19 是周一
        assertEquals(at(2026, 10, 19, 9, 30), weekdays.nextAfter(at(2026, 10, 19, 8, 0), zone))
    }

    @Test
    fun nextAfter_sameDayBetweenTimes() {
        assertEquals(at(2026, 10, 19, 14, 30), weekdays.nextAfter(at(2026, 10, 19, 10, 0), zone))
    }

    @Test
    fun nextAfter_afterLastTimeMovesToNextAllowedDay() {
        assertEquals(at(2026, 10, 20, 9, 30), weekdays.nextAfter(at(2026, 10, 19, 15, 0), zone))
    }

    @Test
    fun nextAfter_skipsWeekend() {
        // 2026-10-23 周五 15:00 → 下一个是周一 09:30
        assertEquals(at(2026, 10, 26, 9, 30), weekdays.nextAfter(at(2026, 10, 23, 15, 0), zone))
    }

    @Test
    fun nextAfter_strictlyAfterNow() {
        // 正好在 09:30 这一毫秒时，next 必须是 14:30
        assertEquals(at(2026, 10, 19, 14, 30), weekdays.nextAfter(at(2026, 10, 19, 9, 30), zone))
    }

    @Test
    fun nextAfter_emptyTimesReturnsNull() {
        assertNull(Recurrence.parse(emptyList(), emptyList()).nextAfter(at(2026, 10, 19, 8, 0), zone))
    }

    @Test
    fun occurrencesBetween_isHalfOpen() {
        val from = at(2026, 10, 19, 0, 0)
        val to = at(2026, 10, 19, 23, 59)
        assertEquals(
            listOf(at(2026, 10, 19, 9, 30), at(2026, 10, 19, 14, 30)),
            weekdays.occurrencesBetween(from, to, zone),
        )
    }

    @Test
    fun occurrencesBetween_respectsLimit() {
        val r = Recurrence.parse(listOf("00:00", "00:01", "00:02"), emptyList())
        val out = r.occurrencesBetween(at(2026, 10, 1, 0, 0), at(2026, 10, 31, 0, 0), zone, limit = 5)
        assertEquals(5, out.size)
    }

    @Test
    fun occurrencesBetween_returnsEmptyWhenRangeEmpty() {
        val t = at(2026, 10, 19, 8, 0)
        assertTrue(weekdays.occurrencesBetween(t, t, zone).isEmpty())
    }

    @Test
    fun describeDays_readable() {
        assertEquals("每天", Recurrence.describeDays(emptySet()))
        assertEquals(
            "工作日",
            Recurrence.describeDays(
                setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
            ),
        )
        assertEquals("周一", Recurrence.describeDays(setOf(DayOfWeek.MONDAY)))
    }

    @Test
    fun describeTimes_sortedAndPadded() {
        assertEquals("07:05、09:30", Recurrence.describeTimes(listOf(LocalTime.of(9, 30), LocalTime.of(7, 5))))
    }
}
