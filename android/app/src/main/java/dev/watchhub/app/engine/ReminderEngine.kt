package dev.watchhub.app.engine

import dev.watchhub.app.core.Clock
import dev.watchhub.app.core.LogBus
import dev.watchhub.app.core.TaskRules
import dev.watchhub.app.core.model.Alert
import dev.watchhub.app.core.model.AlertKind
import dev.watchhub.app.core.model.Task
import dev.watchhub.app.data.AlertStore
import dev.watchhub.app.data.ReminderStore
import dev.watchhub.app.data.TaskStore

/**
 * 提醒引擎：把「重复规则」解释成「具体任务」，把「到期任务」变成「待送达 alert」。
 *
 * 两件事必须幂等，因为所有入口（服务 tick / 闹钟 / 看门狗 / 开机）都会重复调用：
 *  - materialize：靠 UNIQUE(reminder_id, due_at)
 *  - due：靠 tasks.alerted_at + alerts.key 唯一
 */
class ReminderEngine(
    private val reminders: ReminderStore,
    private val tasks: TaskStore,
    private val alerts: AlertStore,
    private val clock: Clock,
) {

    data class DueResult(val delivered: Int, val missed: Int, val late: Int)

    data class ReconcileResult(val materialized: Int, val due: DueResult)

    /** 为所有启用的提醒补齐未来两周的任务。 */
    fun materialize(): Int {
        val now = clock.now()
        var inserted = 0
        reminders.enabled().forEach { reminder ->
            inserted += tasks.materialize(reminder, now, TaskStore.DEFAULT_HORIZON_MS)
        }
        return inserted
    }

    /**
     * 处理所有到期任务：每条提醒只推最近一次过期实例（更早的标 MISSED）。
     * 迟到的正文会写成「迟到 · 原定 09:30 · ...」。
     */
    fun due(): DueResult {
        val now = clock.now()
        val open = tasks.open()
        val plan = TaskRules.plan(open, now)
        plan.missed.forEach { tasks.markMissed(it.id) }

        var delivered = 0
        var late = 0
        plan.deliver.forEach { task ->
            val isLate = TaskRules.isLate(task, now)
            if (isLate) late++
            val reminder = reminders.byId(task.reminderId)
            val alert = Alert(
                title = task.title,
                key = "rem:${task.id}:${task.effectiveDueAt}",
                kind = AlertKind.REMINDER,
                source = reminder?.name.orEmpty(),
                body = if (isLate) TaskRules.lateBody(task, now) else task.body,
                detail = task.body,
                priority = task.priority,
                tags = listOf("reminder"),
                at = now,
                taskId = task.id,
            )
            alerts.insert(alert)
            // 即使 alert 已存在（两步之间进程被杀后重入）也要标记，避免无限重复处理
            tasks.markAlerted(task.id, now)
            delivered++
        }

        if (plan.missed.isNotEmpty()) {
            LogBus.post("${plan.missed.size} 条过期任务标记为错过（不补发）")
        }
        if (delivered > 0) {
            LogBus.post("$delivered 条任务到期" + if (late > 0) "（其中 $late 条迟到）" else "")
        }
        return DueResult(delivered = delivered, missed = plan.missed.size, late = late)
    }

    /** 所有入口共用的对齐动作：补任务 + 处理到期。 */
    fun reconcile(): ReconcileResult {
        val materialized = materialize()
        val due = due()
        return ReconcileResult(materialized, due)
    }

    /** 下一次需要闹钟的时刻；没有 pending 任务返回 null。 */
    fun nextDueAt(): Long? {
        val now = clock.now()
        val pending = tasks.open().filter { it.alertedAt == 0L }
        if (pending.isEmpty()) return null
        val next = pending.minOf { it.effectiveDueAt }
        // 已经过期但还没处理：立刻响，避免因为服务刚好在睡眠而漏掉
        return maxOf(next, now + 1_000L)
    }

    fun complete(taskId: Long): Boolean {
        val t = tasks.byId(taskId) ?: return false
        tasks.complete(taskId, clock.now())
        LogBus.post("完成：${t.title}")
        return true
    }

    fun skip(taskId: Long): Boolean {
        val t = tasks.byId(taskId) ?: return false
        tasks.skip(taskId, clock.now())
        LogBus.post("跳过：${t.title}")
        return true
    }

    /** 稍后再说。默认 10 分钟。 */
    fun snooze(taskId: Long, minutes: Int = 10): Boolean {
        val t = tasks.byId(taskId) ?: return false
        val until = clock.now() + minutes.coerceIn(1, 24 * 60) * 60_000L
        tasks.snooze(taskId, until)
        LogBus.post("稍后 ${minutes} 分钟：${t.title}")
        return true
    }

    /** 删除一条提醒时连带清掉它的任务。 */
    fun deleteReminder(reminderId: Long) {
        tasks.deleteForReminder(reminderId)
        reminders.delete(reminderId)
        LogBus.post("已删除提醒 #$reminderId")
    }
}
