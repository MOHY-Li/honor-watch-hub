package dev.watchhub.app.core

import dev.watchhub.app.core.model.Priority
import dev.watchhub.app.core.model.Task
import dev.watchhub.app.core.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TaskRulesTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun ms(d: Int, h: Int, m: Int): Long =
        ZonedDateTime.of(LocalDate.of(2026, 10, d), LocalTime.of(h, m), zone).toInstant().toEpochMilli()

    private fun task(
        id: Long,
        due: Long,
        reminderId: Long = 1,
        alertedAt: Long = 0,
        snoozedUntil: Long = 0,
        status: TaskStatus = TaskStatus.OPEN,
        body: String = "喝水",
    ) = Task(
        reminderId = reminderId,
        title = "喝水",
        dueAt = due,
        id = id,
        body = body,
        priority = Priority.HIGH,
        status = status,
        alertedAt = alertedAt,
        snoozedUntil = snoozedUntil,
    )

    @Test
    fun plan_deliversEachReminderAtMostOnce() {
        val now = ms(19, 15, 0)
        val open = listOf(task(1, ms(19, 9, 30)), task(2, ms(19, 10, 30)))
        val plan = TaskRules.plan(open, now)
        assertEquals(1, plan.deliver.size)
        assertEquals(2L, plan.deliver[0].id) // 最近的那条
        assertEquals(1, plan.missed.size)
        assertEquals(1L, plan.missed[0].id)
    }

    @Test
    fun plan_ignoresAlreadyAlerted() {
        val now = ms(19, 15, 0)
        val open = listOf(task(1, ms(19, 9, 30), alertedAt = ms(19, 9, 30)))
        assertTrue(TaskRules.plan(open, now).deliver.isEmpty())
    }

    @Test
    fun plan_ignoresNotYetDue() {
        val now = ms(19, 8, 0)
        val open = listOf(task(1, ms(19, 9, 30)))
        assertTrue(TaskRules.plan(open, now).deliver.isEmpty())
    }

    @Test
    fun plan_usesSnoozedTime() {
        val now = ms(19, 15, 0)
        val open = listOf(task(1, ms(19, 9, 30), snoozedUntil = ms(19, 20, 0)))
        assertTrue(TaskRules.plan(open, now).deliver.isEmpty())
        assertTrue(TaskRules.plan(open, ms(19, 20, 1)).deliver.isNotEmpty())
    }

    @Test
    fun plan_independentRemindersDoNotCollide() {
        val now = ms(19, 15, 0)
        val open = listOf(
            task(1, ms(19, 9, 30), reminderId = 1),
            task(2, ms(19, 10, 0), reminderId = 2),
        )
        val plan = TaskRules.plan(open, now)
        assertEquals(2, plan.deliver.size)
        assertTrue(plan.missed.isEmpty())
    }

    @Test
    fun plan_ignoresNonOpenTasks() {
        val now = ms(19, 15, 0)
        val open = listOf(
            task(1, ms(19, 9, 30), status = TaskStatus.DONE),
            task(2, ms(19, 10, 0), status = TaskStatus.MISSED),
        )
        assertTrue(TaskRules.plan(open, now).deliver.isEmpty())
    }

    @Test
    fun isLate_threshold() {
        val due = ms(19, 9, 30)
        val t = task(1, due)
        assertFalse(TaskRules.isLate(t, due + 10_000))
        assertTrue(TaskRules.isLate(t, due + 61_000))
    }

    @Test
    fun lateBody_containsOriginalTime() {
        val t = task(1, ms(19, 9, 30), body = "起身走两步")
        val body = TaskRules.lateBody(t, zone)
        assertTrue(body.startsWith("迟到 · 原定 09:30"))
        assertTrue(body.contains("起身走两步"))
    }

    @Test
    fun lateBody_withoutBody() {
        val t = task(1, ms(19, 9, 30), body = "")
        assertEquals("迟到 · 原定 09:30", TaskRules.lateBody(t, zone))
    }

    @Test
    fun lateBody_timezoneExplicit() {
        val t = task(1, ms(19, 9, 30), body = "")
        // 同一个时间戳在 UTC 下应显示 01:30；证明文案只取决于传入的 zone，与运行环境无关
        assertEquals("迟到 · 原定 01:30", TaskRules.lateBody(t, java.time.ZoneId.of("UTC")))
    }
}
