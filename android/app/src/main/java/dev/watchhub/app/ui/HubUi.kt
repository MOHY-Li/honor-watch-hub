package dev.watchhub.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.watchhub.app.App
import dev.watchhub.app.Container
import dev.watchhub.app.core.LogBus
import dev.watchhub.app.core.model.Alert
import dev.watchhub.app.core.model.Priority
import dev.watchhub.app.core.model.Reminder
import dev.watchhub.app.core.model.Task
import dev.watchhub.app.core.model.TaskStatus
import dev.watchhub.app.health.Health
import dev.watchhub.app.health.HealthAction
import dev.watchhub.app.health.HealthItem
import dev.watchhub.app.health.HealthLevel
import dev.watchhub.app.service.HubService
import dev.watchhub.app.source.SourceDef
import dev.watchhub.app.source.SourceKind
import dev.watchhub.app.update.UpdateCheck
import dev.watchhub.app.update.UpdateManifest
import dev.watchhub.app.update.Updater
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * UI 的唯一入口门面。
 *
 * UI 层不直接摸 store 和 engine：所有读操作在这里变成「可直接渲染的数据类」，
 * 所有写操作在这里做错误处理。这样即使以后换 UI 框架，业务层不用动。
 *
 * 约定：所有方法都可能在主线程调用（数据库操作是毫秒级）；
 * 唯一必须在后台线程调用的是 [checkSourceNow] 和 [checkUpdate]。
 */
object HubUi {

    // ---------------- 数据类（UI 直接渲染） ----------------

    data class Home(
        val wantRun: Boolean,
        val running: Boolean,
        val todayOpen: Int,
        val todayDone: Int,
        val nextDueAt: Long?,
        val nextTitle: String?,
        val nextTimeText: String?,
        val recentAlerts: List<Alert>,
        val health: List<HealthItem>,
        val healthWorst: HealthLevel,
        val sourcesOk: Int,
        val sourcesTotal: Int,
    )

    data class TaskRow(
        val id: Long,
        val title: String,
        val body: String,
        val timeText: String,
        val statusText: String,
        val status: TaskStatus,
        val highPriority: Boolean,
        val late: Boolean,
        val reminderName: String,
    )

    data class TaskSection(val title: String, val rows: List<TaskRow>)

    data class ReminderRow(
        val id: Long,
        val name: String,
        val title: String,
        val scheduleText: String,
        val priorityText: String,
        val enabled: Boolean,
        val calendarSync: Boolean,
    )

    data class SourceRow(
        val name: String,
        val kind: String,
        val enabled: Boolean,
        val statusText: String,
        val level: HealthLevel,
        val intervalText: String,
    )

    data class SettingsSnapshot(
        val startOnBoot: Boolean,
        val calendarEnabled: Boolean,
        val mirrorConfirmed: Boolean,
        val wantRun: Boolean,
    )

    // ---------------- 读取 ----------------

    private fun c(ctx: Context): Container = App.container(ctx)

    fun home(ctx: Context): Home {
        val c = c(ctx)
        val now = c.clock.now()
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val stats = c.tasks.statsBetween(dayStart, dayEnd)
        val next = c.engine.nextDueAt()
        val nextTask = c.tasks.upcoming(1).firstOrNull()
        val health = health(ctx)
        val defs = c.sources.all()
        val ok = defs.count { c.sources.stateOf(it.name).let { s -> s.lastOk > 0 && s.lastError == null } }
        return Home(
            wantRun = c.settings.wantRun,
            running = isRunning(ctx),
            todayOpen = stats.open,
            todayDone = stats.done,
            nextDueAt = next,
            nextTitle = nextTask?.title,
            nextTimeText = nextTask?.let { formatWhen(it.effectiveDueAt) },
            recentAlerts = c.alerts.recent(5),
            health = health,
            healthWorst = worst(health),
            sourcesOk = ok,
            sourcesTotal = defs.size,
        )
    }

    fun taskSections(ctx: Context): List<TaskSection> {
        val c = c(ctx)
        val now = c.clock.now()
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowStart = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val names = c.reminders.all().associate { it.id to it.name }
        val all = c.tasks.recent(300)

        fun row(t: Task) = TaskRow(
            id = t.id,
            title = t.title,
            body = t.body,
            timeText = formatWhen(t.effectiveDueAt),
            statusText = when (t.status) {
                TaskStatus.OPEN -> if (t.effectiveDueAt < now) "待办 · 已过期" else "待办"
                TaskStatus.DONE -> "已完成"
                TaskStatus.SKIPPED -> "已跳过"
                TaskStatus.MISSED -> "已错过"
            },
            status = t.status,
            highPriority = t.priority == Priority.HIGH,
            late = t.status == TaskStatus.OPEN && t.effectiveDueAt < now,
            reminderName = names[t.reminderId].orEmpty(),
        )

        val open = all.filter { it.status == TaskStatus.OPEN }.sortedBy { it.effectiveDueAt }
        val overdue = open.filter { it.effectiveDueAt < now }
        val today = open.filter { it.effectiveDueAt >= now && it.effectiveDueAt < tomorrowStart }
        val later = open.filter { it.effectiveDueAt >= tomorrowStart }
        val done = all.filter { it.status != TaskStatus.OPEN }
            .sortedByDescending { it.doneAt.takeIf { d -> d > 0 } ?: it.dueAt }
            .take(20)

        val sections = ArrayList<TaskSection>()
        if (overdue.isNotEmpty()) sections.add(TaskSection("已过期", overdue.map(::row)))
        if (today.isNotEmpty()) sections.add(TaskSection("今天", today.map(::row)))
        if (later.isNotEmpty()) sections.add(TaskSection("之后", later.map(::row)))
        if (done.isNotEmpty()) sections.add(TaskSection("最近完成", done.map(::row)))
        return sections
    }

    fun reminderRows(ctx: Context): List<ReminderRow> {
        val c = c(ctx)
        return c.reminders.all().map { r ->
            ReminderRow(
                id = r.id,
                name = r.name,
                title = r.title,
                scheduleText = dev.watchhub.app.core.model.Recurrence.describeDays(r.recurrence.days) +
                    " " + dev.watchhub.app.core.model.Recurrence.describeTimes(r.recurrence.times),
                priorityText = priorityText(r.priority),
                enabled = r.enabled,
                calendarSync = r.calendarSync,
            )
        }
    }

    fun reminderById(ctx: Context, id: Long): Reminder? = c(ctx).reminders.byId(id)

    fun sourceRows(ctx: Context): List<SourceRow> {
        val c = c(ctx)
        val now = c.clock.now()
        return c.sources.all().map { def ->
            val s = c.sources.stateOf(def.name)
            val (text, level) = when {
                !def.enabled -> "已停用" to HealthLevel.INFO
                s.lastError != null && s.fails > 0 ->
                    "连续 ${s.fails} 次失败：${s.lastError.take(80)}" to
                        if (s.fails >= 3) HealthLevel.FAIL else HealthLevel.WARN
                s.lastOk > 0 -> "OK · ${ago(now - s.lastOk)}前 · 累计 ${s.itemsSeen} 条" to HealthLevel.OK
                else -> "还没检查" to HealthLevel.INFO
            }
            SourceRow(
                name = def.name,
                kind = def.kind.wire,
                enabled = def.enabled,
                statusText = text,
                level = level,
                intervalText = "每 ${def.intervalSec}s",
            )
        }
    }

    fun rawSourcesJson(ctx: Context): String = c(ctx).sources.rawJson()

    fun recentAlerts(ctx: Context, limit: Int = 100): List<Alert> = c(ctx).alerts.recent(limit)

    fun alertById(ctx: Context, id: Long): Alert? = c(ctx).alerts.byId(id)

    fun logs(): List<String> = LogBus.tail(300)

    fun clearLogs() = LogBus.clear()

    fun health(ctx: Context): List<HealthItem> {
        val c = c(ctx)
        return Health(c.ctx, c.settings, c.alerts, c.sources, c.clock).check()
    }

    fun settings(ctx: Context): SettingsSnapshot {
        val c = c(ctx)
        return SettingsSnapshot(
            startOnBoot = c.settings.startOnBoot,
            calendarEnabled = c.settings.calendarEnabled,
            mirrorConfirmed = c.settings.mirrorConfirmed,
            wantRun = c.settings.wantRun,
        )
    }

    fun isRunning(ctx: Context): Boolean {
        val c = c(ctx)
        val age = c.clock.now() - c.settings.heartbeatAt
        return c.settings.wantRun && c.settings.heartbeatAt > 0 && age in 0..RUNNING_WINDOW_MS
    }

    fun sourceJsonForEdit(ctx: Context, name: String): String? {
        val def = c(ctx).sources.get(name) ?: return null
        return def.toJson()
    }

    fun sourceTemplate(kind: SourceKind): String = when (kind) {
        SourceKind.RSS -> """{"name":"my_rss","kind":"rss","url":"https://example.com/feed.xml","interval":900,"priority":"high","limit":20}"""
        SourceKind.JSON -> """{"name":"my_json","kind":"json","url":"https://api.example.com/list","list_path":"data","limit":20,"interval":900,"fields":{"title":"name","key":"id","body":"time","url":"link"}}"""
        SourceKind.REGEX -> """{"name":"my_regex","kind":"regex","url":"https://example.com/news","pattern":"<a[^>]+href=\"(?<url>[^\"]+)\"[^>]*>(?<title>[^<]+)</a>","limit":20,"interval":900}"""
        SourceKind.HASH -> """{"name":"my_page","kind":"hash","url":"https://example.com/product/123","interval":300,"title":"页面有变化","body":"快去看看","priority":"high"}"""
    }

    // ---------------- 写操作 ----------------

    fun start(ctx: Context) {
        c(ctx).settings.wantRun = true
        HubService.start(ctx)
        reconcileAsync(ctx)
    }

    fun stop(ctx: Context) {
        val c = c(ctx)
        c.settings.wantRun = false
        c.scheduler.cancel()
        HubService.stop(ctx)
        LogBus.post("中枢已停止（提醒不再触发）")
    }

    fun completeTask(ctx: Context, taskId: Long) {
        c(ctx).engine.complete(taskId)
        reconcileAsync(ctx)
    }

    fun skipTask(ctx: Context, taskId: Long) {
        c(ctx).engine.skip(taskId)
        reconcileAsync(ctx)
    }

    fun snoozeTask(ctx: Context, taskId: Long, minutes: Int = 10) {
        c(ctx).engine.snooze(taskId, minutes)
        reconcileAsync(ctx)
    }

    fun deleteReminder(ctx: Context, id: Long) {
        val c = c(ctx)
        c.engine.deleteReminder(id)
        reconcileAsync(ctx)
    }

    fun setReminderEnabled(ctx: Context, id: Long, enabled: Boolean) {
        c(ctx).reminders.setEnabled(id, enabled)
        reconcileAsync(ctx)
    }

    /** 保存提醒。重名会返回失败，UI 直接把 message 弹给用户。 */
    fun saveReminder(ctx: Context, reminder: Reminder): Result<Long> = runCatching {
        val c = c(ctx)
        val id = c.reminders.upsert(reminder)
        reconcileAsync(ctx)
        id
    }.recoverCatching { t ->
        throw IllegalArgumentException(if (t.message?.contains("UNIQUE", true) == true) "提醒名字已被占用" else t.message ?: "保存失败")
    }

    fun saveSourcesJson(ctx: Context, json: String): Result<Unit> = runCatching {
        c(ctx).sources.replaceAll(json)
        reconcileAsync(ctx)
    }.recoverCatching { t ->
        throw IllegalArgumentException(t.message ?: "保存失败")
    }

    fun setSourceEnabled(ctx: Context, name: String, enabled: Boolean) {
        c(ctx).sources.setEnabled(name, enabled)
    }

    fun deleteSource(ctx: Context, name: String) {
        c(ctx).sources.delete(name)
        LogBus.post("已删除数据源 $name")
    }

    /** 阻塞网络调用，必须在后台线程调用。 */
    fun checkSourceNow(ctx: Context, name: String): Int = c(ctx).poller.checkNow(name)

    fun testNotification(ctx: Context): Boolean = c(ctx).deliverer.testNotification()

    fun setStartOnBoot(ctx: Context, value: Boolean) {
        c(ctx).settings.startOnBoot = value
    }

    fun setCalendarEnabled(ctx: Context, value: Boolean) {
        c(ctx).settings.calendarEnabled = value
    }

    fun setMirrorConfirmed(ctx: Context, value: Boolean) {
        c(ctx).settings.mirrorConfirmed = value
    }

    /** 立即对齐一次（补任务、派发 outbox、排闹钟）。 */
    fun reconcile(ctx: Context) {
        runCatching { c(ctx).reconciler.run("ui") }
    }

    fun reconcileAsync(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ reconcile(app) }, "watchhub-ui-reconcile").start()
    }

    // ---------------- 体检动作 ----------------

    /**
     * 执行体检项的动作。
     *
     * 返回 false 表示「需要 UI 自己申请运行时权限」（通知权限 / 日历权限），
     * 其余情况这里直接跳转或启动服务。
     */
    fun performAction(ctx: Context, action: HealthAction): Boolean = when (action) {
        HealthAction.NOTIFICATION_PERMISSION, HealthAction.CALENDAR_PERMISSION -> false
        HealthAction.CHANNEL_SETTINGS -> {
            val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, dev.watchhub.app.delivery.Notifier.CHANNEL_ALERTS)
            } else {
                notificationSettingsIntent(ctx)
            }
            open(ctx, intent)
            true
        }
        HealthAction.APP_NOTIFICATION_SETTINGS -> {
            open(ctx, notificationSettingsIntent(ctx))
            true
        }
        HealthAction.EXACT_ALARM -> {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                open(ctx, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(ctx)))
            } else {
                open(ctx, appDetailsIntent(ctx))
            }
            true
        }
        HealthAction.BATTERY_OPTIMIZATION -> {
            open(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(ctx)))
            true
        }
        HealthAction.START_SERVICE -> {
            start(ctx)
            true
        }
        HealthAction.OPEN_APP_SETTINGS -> {
            open(ctx, appDetailsIntent(ctx))
            true
        }
        HealthAction.MIRROR_SETTINGS -> {
            val found = Health(c(ctx).ctx, c(ctx).settings, c(ctx).alerts, c(ctx).sources, c(ctx).clock).findMirrorApp()
            val launch = found?.first?.let { pkg -> ctx.packageManager.getLaunchIntentForPackage(pkg) }
            open(ctx, launch ?: appDetailsIntent(ctx))
            true
        }
    }

    // ---------------- 自更新 ----------------

    /** 阻塞网络调用，必须在后台线程调用。 */
    fun checkUpdate(ctx: Context): UpdateCheck = Updater.check()

    fun downloadUpdate(ctx: Context, manifest: UpdateManifest, onDone: (Boolean, String) -> Unit): Long =
        Updater.download(ctx, manifest, onDone)

    fun installUpdate(ctx: Context): Updater.InstallResult = Updater.install(ctx)

    fun versionName(): String = Updater.currentVersionName()

    fun versionCode(): Long = Updater.currentVersionCode()

    // ---------------- 内部 ----------------

    private const val RUNNING_WINDOW_MS = 2 * 60_000L

    private fun worst(items: List<HealthItem>): HealthLevel = when {
        items.any { it.level == HealthLevel.FAIL } -> HealthLevel.FAIL
        items.any { it.level == HealthLevel.WARN } -> HealthLevel.WARN
        else -> HealthLevel.OK
    }

    private fun priorityText(p: Priority): String = when (p) {
        Priority.HIGH -> "高"
        Priority.NORMAL -> "普通"
        Priority.LOW -> "低"
    }

    private fun formatWhen(ms: Long): String {
        val zone = ZoneId.systemDefault()
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        val today = java.time.LocalDate.now(zone)
        return when (t.toLocalDate()) {
            today -> t.format(HH_MM)
            today.plusDays(1) -> "明天 " + t.format(HH_MM)
            else -> t.format(MM_DD_HH_MM)
        }
    }

    private fun ago(ms: Long): String = when {
        ms < 60_000 -> "${ms / 1000}s"
        ms < 3600_000 -> "${ms / 60_000}m"
        ms < 24 * 3600_000L -> "${ms / 3600_000}h"
        else -> "${ms / (24 * 3600_000L)}d"
    }

    private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val MM_DD_HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    private fun packageUri(ctx: Context): Uri = Uri.parse("package:${ctx.packageName}")

    private fun appDetailsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(ctx))

    private fun notificationSettingsIntent(ctx: Context): Intent =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        } else {
            appDetailsIntent(ctx)
        }

    private fun open(ctx: Context, intent: Intent) {
        runCatching {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { LogBus.post("跳转失败: ${it.message}") }
    }
}
