package dev.watchhub.app.engine

import dev.watchhub.app.core.Clock
import dev.watchhub.app.core.CronExpression
import dev.watchhub.app.core.EscalationPolicy
import dev.watchhub.app.core.LogBus
import dev.watchhub.app.core.TaskRules
import dev.watchhub.app.core.model.Alert
import dev.watchhub.app.core.model.AlertKind
import dev.watchhub.app.core.model.Reminder
import dev.watchhub.app.core.model.ScheduleMode
import dev.watchhub.app.core.model.Task
import dev.watchhub.app.core.model.TaskStatus
import dev.watchhub.app.data.AlertStore
import dev.watchhub.app.data.ReminderStore
import dev.watchhub.app.data.TaskStore
import java.time.Instant
import java.time.ZoneId

/**
 * 提醒引擎：调度、提醒、升级链。
 *
 * 三种模式：
 * - TIMES：沿用「星期 + 时间点」，提前补任务（materialize）。
 * - INTERVAL：基础间隔（默认 25 分钟）从上次确认算起；未确认进入升级链。
 * - CRON：标准 5 段表达式到点触发；未确认同样升级，下一次 cron 到点重置阶梯。
 *
 * 升级链：首次提醒后 level=0；未确认按 20/15/10/5/1 分钟 nudge，之后每 1 分钟；
 * 跨天重置为基础间隔；确认（完成/跳过/稍后）结束链条。
 */
class ReminderEngine(
    private val reminders: ReminderStore,
    private val tasks: TaskStore,
    private val alerts: AlertStore,
    private val clock: Clock,
) {

    data class DueResult(val delivered: Int, val missed: Int, val late: Int, val nudged: Int)

    data class ReconcileResult(val materialized: Int, val due: DueResult)

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** 为启用的提醒补齐下一次触发（TIMES 提前补 7 天）。 */
    fun materialize(): Int {
        val now = clock.now()
        var inserted = 0
        reminders.enabled().forEach { reminder ->
            when (reminder.mode) {
                ScheduleMode.TIMES -> {
                    inserted += tasks.materialize(reminder, now, TaskStore.DEFAULT_HORIZON_MS)
                }
                ScheduleMode.INTERVAL -> {
                    val latest = tasks.latestFor(reminder.id)
                    if (needsNext(latest)) {
                        val base = latest?.doneAt?.takeIf { it > 0 } ?: now
                        if (tasks.insertOccurrence(reminder, base + intervalMs(reminder), now) != null) inserted++
                    }
                }
                ScheduleMode.CRON -> {
                    val latest = tasks.latestFor(reminder.id)
                    if (needsNext(latest)) {
                        nextCronDue(reminder, now)?.let { due ->
                            if (tasks.insertOccurrence(reminder, due, now) != null) inserted++
                        }
                    }
                }
            }
        }
        return inserted
    }

    /** 处理所有到期：首次提醒 + 升级链 nudge。 */
    fun due(): DueResult {
        val now = clock.now()
        val open = tasks.open()
        val plan = TaskRules.plan(open, now)
        plan.missed.forEach { tasks.markMissed(it.id) }

        var delivered = 0
        var late = 0
        plan.deliver.forEach { task ->
            val reminder = reminders.byId(task.reminderId) ?: return@forEach
            // 新的 occurrence 生效：同提醒更早的 OPEN 任务（含还挂在升级链上的）标为错过
            tasks.markOlderOpenMissed(reminder.id, task.dueAt, task.id)
            val isLate = TaskRules.isLate(task, now)
            if (isLate) late++
            alerts.insert(
                Alert(
                    title = task.title,
                    key = "rem:${task.id}:${task.effectiveDueAt}",
                    kind = AlertKind.REMINDER,
                    source = reminder.name,
                    body = if (isLate) TaskRules.lateBody(task, zone) else task.body,
                    detail = task.body,
                    priority = task.priority,
                    tags = listOf("reminder"),
                    at = now,
                    taskId = task.id,
                )
            )
            tasks.startChain(task.id, now)
            delivered++
            if (reminder.mode == ScheduleMode.CRON) {
                nextCronDue(reminder, task.dueAt)?.let { next ->
                    tasks.insertOccurrence(reminder, next, now)
                }
            }
        }

        // 升级链：已提醒但未确认的任务，按阶梯再次提醒
        var nudged = 0
        tasks.openEscalating().forEach { task ->
            val reminder = reminders.byId(task.reminderId) ?: return@forEach
            if (reminder.mode == ScheduleMode.TIMES) return@forEach

            if (isNewDay(task.chainStartedAt, now)) {
                // 跨天：按基础间隔重新开始一次干净提醒
                tasks.resetToBase(task.id, now, EscalationPolicy.BASE_INTERVAL_MIN * 60_000L)
                return@forEach
            }
            val nextAt = task.alertedAt + EscalationPolicy.intervalFor(task.escalationLevel) * 60_000L
            if (now < nextAt) return@forEach

            val level = task.escalationLevel + 1
            alerts.insert(
                Alert(
                    title = task.title,
                    key = "rem:${task.id}:${task.alertedAt}",
                    kind = AlertKind.REMINDER,
                    source = reminder.name,
                    body = "还没确认 · 第 ${level} 次提醒 · ${task.body}".trim(),
                    detail = task.body,
                    priority = task.priority,
                    tags = listOf("reminder"),
                    at = now,
                    taskId = task.id,
                )
            )
            tasks.nudge(task.id, now)
            nudged++
            delivered++
        }

        if (plan.missed.isNotEmpty()) LogBus.post("${plan.missed.size} 条过期任务标记为错过（不补发）")
        if (delivered > 0) {
            LogBus.post("$delivered 条提醒触发" + if (nudged > 0) "（升级 nudge $nudged）" else "")
        }
        return DueResult(delivered = delivered, missed = plan.missed.size, late = late, nudged = nudged)
    }

    fun reconcile(): ReconcileResult = ReconcileResult(materialize(), due())

    /** 下一次需要闹钟的时刻：首次到期或下一次 nudge。 */
    fun nextDueAt(): Long? {
        val now = clock.now()
        val candidates = ArrayList<Long>()
        tasks.open().forEach { task ->
            if (task.alertedAt == 0L) {
                candidates.add(task.effectiveDueAt)
            } else if (task.snoozedUntil == 0L) {
                val reminder = reminders.byId(task.reminderId) ?: return@forEach
                if (reminder.mode == ScheduleMode.TIMES) return@forEach
                candidates.add(task.alertedAt + EscalationPolicy.intervalFor(task.escalationLevel) * 60_000L)
            }
        }
        if (candidates.isEmpty()) return null
        return maxOf(candidates.min(), now + 1_000L)
    }

    fun hasActiveEscalation(): Boolean = tasks.hasActiveEscalation()

    fun complete(taskId: Long): Boolean {
        val task = tasks.byId(taskId) ?: return false
        val now = clock.now()
        tasks.complete(taskId, now)
        scheduleNextAfterAck(task, now)
        LogBus.post("完成：${task.title}")
        return true
    }

    fun skip(taskId: Long): Boolean {
        val task = tasks.byId(taskId) ?: return false
        val now = clock.now()
        tasks.skip(taskId, now)
        scheduleNextAfterAck(task, now)
        LogBus.post("跳过：${task.title}")
        return true
    }

    /** 稍后：延后 [minutes] 分钟，升级链归零，之后按基础间隔。 */
    fun snooze(taskId: Long, minutes: Int = 10): Boolean {
        val task = tasks.byId(taskId) ?: return false
        val until = clock.now() + minutes.coerceIn(1, 24 * 60) * 60_000L
        tasks.snooze(taskId, until)
        LogBus.post("稍后 ${minutes} 分钟：${task.title}")
        return true
    }

    fun deleteReminder(reminderId: Long) {
        tasks.deleteForReminder(reminderId)
        reminders.delete(reminderId)
        LogBus.post("已删除提醒 #$reminderId")
    }

    // ---------------- 内部 ----------------

    private fun intervalMs(reminder: Reminder): Long =
        reminder.intervalMin.coerceAtLeast(1) * 60_000L

    private fun needsNext(latest: Task?): Boolean =
        latest == null ||
            latest.status == TaskStatus.DONE ||
            latest.status == TaskStatus.SKIPPED ||
            latest.status == TaskStatus.MISSED

    /** 完成/跳过一条间隔提醒后，安排下一次；cron 的下一次在触发时已插入。 */
    private fun scheduleNextAfterAck(task: Task, now: Long) {
        val reminder = reminders.byId(task.reminderId) ?: return
        if (reminder.mode != ScheduleMode.INTERVAL) return
        tasks.insertOccurrence(reminder, now + intervalMs(reminder), now)
    }

    private fun nextCronDue(reminder: Reminder, after: Long): Long? {
        val expr = CronExpression.parse(reminder.cron)
        if (expr == null) {
            LogBus.post("[${reminder.name}] cron 表达式无效：${reminder.cron}")
            return null
        }
        return expr.nextAfter(after, zone)
    }

    private fun isNewDay(chainStartedAt: Long, now: Long): Boolean {
        if (chainStartedAt <= 0) return false
        val start = Instant.ofEpochMilli(chainStartedAt).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return start != today
    }
}
