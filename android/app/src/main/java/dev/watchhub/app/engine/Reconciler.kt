package dev.watchhub.app.engine

import dev.watchhub.app.core.Clock
import dev.watchhub.app.core.LogBus
import dev.watchhub.app.data.SettingsStore
import dev.watchhub.app.delivery.CalendarSync
import dev.watchhub.app.delivery.Deliverer

/**
 * 所有唤醒入口共用的「对齐」动作，避免 boot / 闹钟 / 看门狗 / 服务各自写一份逻辑。
 *
 * 顺序有讲究：先迁移，再补任务，再处理到期，再派发 outbox，最后排下一次闹钟。
 * 任何一步失败都不影响后面的步骤（各自 runCatching）。
 *
 * `wantRun == false` 时只做数据库迁移，不触发提醒、不排闹钟——「停止中枢」是总开关。
 */
class Reconciler(
    private val migrator: Migrator,
    private val reminders: ReminderEngine,
    private val deliverer: Deliverer,
    private val scheduler: Scheduler,
    private val calendar: CalendarSync,
    private val settings: SettingsStore,
    private val clock: Clock,
) {

    data class Report(
        val reason: String,
        val materialized: Int,
        val due: Int,
        val missed: Int,
        val late: Int,
        val dispatched: Int,
        val calendarChanged: Int,
        val nextDueAt: Long?,
        val stopped: Boolean = false,
    )

    fun run(reason: String): Report {
        val start = clock.now()
        runCatching { migrator.migrateIfNeeded() }
            .onFailure { LogBus.post("迁移失败: ${it.message}") }

        if (!settings.wantRun) {
            runCatching { scheduler.armAt(null) }
            return Report(reason, 0, 0, 0, 0, 0, 0, null, stopped = true)
        }

        val materialized = runCatching { reminders.materialize() }
            .onFailure { LogBus.post("补齐任务失败: ${it.message}") }
            .getOrDefault(0)

        val due = runCatching { reminders.due() }
            .onFailure { LogBus.post("处理到期失败: ${it.message}") }
            .getOrNull()

        val dispatched = runCatching { deliverer.dispatchPending() }
            .onFailure { LogBus.post("派发失败: ${it.message}") }
            .getOrDefault(0)

        val calendarChanged = runCatching { calendar.sync() }
            .onFailure { LogBus.post("日历同步失败: ${it.message}") }
            .getOrDefault(0)

        val next = runCatching { reminders.nextDueAt() }.getOrNull()
        runCatching { scheduler.armAt(next) }
            .onFailure { LogBus.post("排闹钟失败: ${it.message}") }

        val report = Report(
            reason = reason,
            materialized = materialized,
            due = due?.delivered ?: 0,
            missed = due?.missed ?: 0,
            late = due?.late ?: 0,
            dispatched = dispatched,
            calendarChanged = calendarChanged,
            nextDueAt = next,
        )
        if (report.due > 0 || report.dispatched > 0 || report.late > 0) {
            LogBus.post(
                "对齐($reason) ${clock.now() - start}ms：新任务${report.materialized} 到期${report.due}" +
                    " 迟到${report.late} 错过${report.missed} 送达${report.dispatched}"
            )
        }
        return report
    }
}
