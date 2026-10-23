package dev.watchhub.app.core

import java.time.Instant
import java.time.ZoneId

/**
 * 提醒确认升级链的纯策略。
 *
 * 语义（见 docs/specs/2026-10-23-watchhub-2.3.0-schedule-escalation.md）：
 * - 基础间隔 25 分钟（从上次确认算起）；
 * - 未确认时依次用 20/15/10/5/1 分钟重提醒，之后每 1 分钟；
 * - 跨天（本地日期变化）重置回基础间隔；
 * - 一个 level 对应「第几次未确认重提醒」，0 表示第一次。
 *
 * 手表端无法回传确认，确认只来自手机端操作。
 */
object EscalationPolicy {

    /** 基础间隔：每 25 分钟一次。 */
    const val BASE_INTERVAL_MIN = 25

    /** 未确认时的重提醒间隔（分钟），用完后保持最后一档。 */
    val DEFAULT_LADDER: List<Int> = listOf(20, 15, 10, 5, 1)

    /** 第 [level] 次未确认重提醒的间隔（分钟），最低 1 分钟。 */
    fun intervalFor(level: Int, ladder: List<Int> = DEFAULT_LADDER): Int {
        if (ladder.isEmpty()) return 1
        val index = level.coerceAtLeast(0)
        val value = if (index < ladder.size) ladder[index] else ladder.last()
        return value.coerceAtLeast(1)
    }

    fun nextLevel(level: Int): Int = (level + 1).coerceAtLeast(0)

    /**
     * 跨天重置：链的起点和现在不在同一个本地日期，就回到 0；
     * 同一天保持原 level。chainStartedAt <= 0 表示没有链，保持原值。
     */
    fun resetIfNewDay(
        level: Int,
        chainStartedAt: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int {
        if (level <= 0 || chainStartedAt <= 0L) return level.coerceAtLeast(0)
        val startDate = Instant.ofEpochMilli(chainStartedAt).atZone(zone).toLocalDate()
        val nowDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (startDate != nowDate) 0 else level
    }

    fun ladderForDisplay(ladder: List<Int> = DEFAULT_LADDER): String =
        ladder.joinToString("/") + " 分钟"
}
