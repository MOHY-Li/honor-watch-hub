package dev.watchhub.app.core.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 重复规则。纯函数，全部通过 [ZoneId] 解释本地时间，可在 JVM 单测里精确验证。
 *
 * - [times] 是本地时间点，允许为空（此时永不触发）。
 * - [days] 是 ISO 星期（周一是 [DayOfWeek.MONDAY]）；空集合表示每天。
 */
data class Recurrence(
    val times: List<LocalTime>,
    val days: Set<DayOfWeek> = emptySet(),
) {

    val daily: Boolean get() = days.isEmpty()

    fun isAllowed(date: LocalDate): Boolean = days.isEmpty() || date.dayOfWeek in days

    /** 严格晚于 [afterMs] 的下一次到期；没有可用时间点返回 null。 */
    fun nextAfter(afterMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (times.isEmpty()) return null
        val sorted = times.sorted()
        val after = Instant.ofEpochMilli(afterMs).atZone(zone)
        var date = after.toLocalDate()
        // 最多看 9 天：一周必然覆盖所有允许的星期几，多一天兜住边界
        for (i in 0..8) {
            if (isAllowed(date)) {
                for (t in sorted) {
                    val ts = ZonedDateTime.of(date, t, zone).toInstant().toEpochMilli()
                    if (ts > afterMs) return ts
                }
            }
            date = date.plusDays(1)
        }
        return null
    }

    /** (fromMs, toMs] 内的所有到期点。有 limit 兜底，防止配置写错时生成过多任务。 */
    fun occurrencesBetween(
        fromMs: Long,
        toMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        limit: Int = 500,
    ): List<Long> {
        if (times.isEmpty() || toMs <= fromMs) return emptyList()
        val sorted = times.sorted()
        val start = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
        val end = Instant.ofEpochMilli(toMs).atZone(zone).toLocalDate()
        val out = ArrayList<Long>()
        var date = start
        while (!date.isAfter(end) && out.size < limit) {
            if (isAllowed(date)) {
                for (t in sorted) {
                    val ts = ZonedDateTime.of(date, t, zone).toInstant().toEpochMilli()
                    if (ts > fromMs && ts <= toMs) out.add(ts)
                }
            }
            date = date.plusDays(1)
        }
        return out.sorted()
    }

    companion object {
        /** 解析配置：`["09:30","14:00"]` + `[0,1,2,3,4]`（0=周一）。非法项被丢弃。 */
        fun parse(timesRaw: List<String>, daysRaw: List<Int>): Recurrence {
            val times = timesRaw.mapNotNull { parseTime(it) }.distinct().sorted()
            val days = daysRaw
                .filter { it in 0..6 }
                .map { DayOfWeek.of(it + 1) }
                .toSet()
            return Recurrence(times, days)
        }

        fun parseTime(raw: String): LocalTime? {
            val t = raw.trim().replace('：', ':')
            val m = Regex("^(\\d{1,2}):(\\d{2})$").find(t) ?: return null
            val h = m.groupValues[1].toIntOrNull() ?: return null
            val mi = m.groupValues[2].toIntOrNull() ?: return null
            if (h !in 0..23 || mi !in 0..59) return null
            return LocalTime.of(h, mi)
        }

        fun describeTimes(times: List<LocalTime>): String =
            times.sorted().joinToString("、") { "%02d:%02d".format(it.hour, it.minute) }

        fun describeDays(days: Set<DayOfWeek>): String {
            if (days.isEmpty()) return "每天"
            val names = mapOf(
                DayOfWeek.MONDAY to "一", DayOfWeek.TUESDAY to "二", DayOfWeek.WEDNESDAY to "三",
                DayOfWeek.THURSDAY to "四", DayOfWeek.FRIDAY to "五", DayOfWeek.SATURDAY to "六",
                DayOfWeek.SUNDAY to "日",
            )
            if (days.size == 5 && days.containsAll(listOf(
                    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
                ))
            ) return "工作日"
            if (days.size == 7) return "每天"
            return "周" + DayOfWeek.entries.filter { it in days }.joinToString("") { names[it] ?: "" }
        }
    }
}
