package dev.watchhub.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.SortedSet

/**
 * 标准 5 段 cron 表达式（分 时 日 月 周），本地时区解释。
 *
 * 支持：星号（全部）、星号斜杠 n（步长）、单值、a-b 范围、a-b/n 步长、逗号列表；
 * 周字段 0 和 7 都表示周日。
 * 不支持：秒字段、月份/星期英文名、@daily 这类别名（用不到，保持实现小）。
 *
 * 日/周的匹配遵循 cron 惯例：两者都被限制时取「或」，只有一个被限制时只看那个。
 */
class CronExpression private constructor(
    private val minutes: SortedSet<Int>,
    private val hours: SortedSet<Int>,
    private val daysOfMonth: SortedSet<Int>,
    private val months: SortedSet<Int>,
    private val daysOfWeek: SortedSet<Int>,
    private val domRestricted: Boolean,
    private val dowRestricted: Boolean,
) {

    fun matches(zdt: ZonedDateTime): Boolean {
        if (zdt.minute !in minutes) return false
        if (zdt.hour !in hours) return false
        if (zdt.monthValue !in months) return false
        return dayMatches(zdt.toLocalDate())
    }

    /** 严格晚于 [afterMs] 的下一次触发；一年半内无解返回 null。 */
    fun nextAfter(afterMs: Long, zone: ZoneId): Long? {
        val after = Instant.ofEpochMilli(afterMs).atZone(zone)
        val startDate = after.toLocalDate()
        for (offset in 0..MAX_DAYS) {
            val date = startDate.plusDays(offset.toLong())
            if (date.monthValue !in months) continue
            if (!dayMatches(date)) continue
            for (h in hours) {
                for (m in minutes) {
                    val ts = ZonedDateTime.of(date, LocalTime.of(h, m), zone).toInstant().toEpochMilli()
                    if (ts > afterMs) return ts
                }
            }
        }
        return null
    }

    private fun dayMatches(date: LocalDate): Boolean {
        val dom = date.dayOfMonth in daysOfMonth
        // java.time: 周一=1 … 周日=7；cron: 周日=0
        val dow = (date.dayOfWeek.value % 7) in daysOfWeek
        return when {
            domRestricted && dowRestricted -> dom || dow
            domRestricted -> dom
            dowRestricted -> dow
            else -> true
        }
    }

    companion object {
        /** 覆盖闰年 2 月 29 日：约 4.1 年。 */
        private const val MAX_DAYS = 1500

        fun parse(expr: String): CronExpression? {
            val parts = expr.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size != 5) return null

            val minutes = parseField(parts[0], 0, 59) ?: return null
            val hours = parseField(parts[1], 0, 23) ?: return null
            val dom = parseField(parts[2], 1, 31) ?: return null
            val months = parseField(parts[3], 1, 12) ?: return null
            val dowRaw = parseField(parts[4], 0, 7) ?: return null
            val dow = sortedSetOf<Int>().apply {
                dowRaw.forEach { add(if (it == 7) 0 else it) }
            }

            return CronExpression(
                minutes = minutes,
                hours = hours,
                daysOfMonth = dom,
                months = months,
                daysOfWeek = dow,
                domRestricted = parts[2].trim() != "*",
                dowRestricted = parts[4].trim() != "*",
            )
        }

        private fun parseField(raw: String, min: Int, max: Int): SortedSet<Int>? {
            val out = sortedSetOf<Int>()
            for (part in raw.split(',')) {
                if (part.isBlank()) return null
                val slash = part.split('/')
                if (slash.size > 2) return null
                val step = if (slash.size == 2) {
                    slash[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
                } else 1
                val base = slash[0]
                val range: IntRange = if (base == "*") {
                    min..max
                } else {
                    val dash = base.split('-')
                    when (dash.size) {
                        1 -> {
                            val v = dash[0].toIntOrNull() ?: return null
                            if (v !in min..max) return null
                            v..v
                        }
                        2 -> {
                            val a = dash[0].toIntOrNull() ?: return null
                            val b = dash[1].toIntOrNull() ?: return null
                            if (a !in min..max || b !in min..max || a > b) return null
                            a..b
                        }
                        else -> return null
                    }
                }
                var value = range.first
                while (value <= range.last) {
                    out.add(value)
                    value += step
                }
            }
            return if (out.isEmpty()) null else out
        }
    }
}
