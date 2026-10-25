package dev.watchhub.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.watchhub.app.App
import dev.watchhub.app.Container
import dev.watchhub.app.core.Backoff
import dev.watchhub.app.core.CronExpression
import dev.watchhub.app.core.EscalationPolicy
import dev.watchhub.app.core.LogBus
import dev.watchhub.app.core.Text
import dev.watchhub.app.core.TtlCache
import dev.watchhub.app.core.model.Alert
import dev.watchhub.app.core.model.Article
import dev.watchhub.app.core.model.Priority
import dev.watchhub.app.core.model.Reminder
import dev.watchhub.app.core.model.ScheduleMode
import dev.watchhub.app.core.model.Task
import dev.watchhub.app.core.model.TaskStatus
import dev.watchhub.app.health.Health
import dev.watchhub.app.health.HealthAction
import dev.watchhub.app.health.HealthItem
import dev.watchhub.app.health.HealthLevel
import dev.watchhub.app.service.HubService
import dev.watchhub.app.source.FeedDiscovery
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
        val nextDueAt: Long?,
        val nextTitle: String?,
        val nextTimeText: String?,
        val health: List<HealthItem>,
        val healthWorst: HealthLevel,
        val sourcesOk: Int,
        val sourcesTotal: Int,
        val sourceStates: List<SourceStatusRow>,
    )

    /** 首页数据源状态行（所有 kind）。 */
    data class SourceStatusRow(
        val name: String,
        val kind: String,
        val enabled: Boolean,
        val statusText: String,
        val level: HealthLevel,
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
        /** 下一次触发的展示文案（暂停/待排期也会给出来）。 */
        val nextText: String,
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
        val wantRun: Boolean,
    )

    /** 订阅页的订阅源一行。 */
    data class SubscriptionRow(
        val name: String,
        val unread: Int,
        val enabled: Boolean,
        val statusText: String,
    )

    /** 订阅页的文章一行。 */
    data class ArticleRow(
        val id: Long,
        val title: String,
        val source: String,
        val url: String,
        val summary: String,
        val timeText: String,
        val unread: Boolean,
    )

    // ---------------- 读取 ----------------

    private fun c(ctx: Context): Container = App.container(ctx)

    fun home(ctx: Context): Home {
        val c = c(ctx)
        val now = c.clock.now()
        val next = c.engine.nextDueAt()
        val nextTask = c.tasks.upcoming(1).firstOrNull()
        val health = health(ctx)
        val defs = c.sources.all()
        val sourceStates = defs.map { def ->
            val st = c.sources.stateOf(def.name)
            val level = when {
                !def.enabled -> HealthLevel.INFO
                st.fails >= 3 -> HealthLevel.FAIL
                st.lastError != null -> HealthLevel.WARN
                st.lastOk > 0 -> HealthLevel.OK
                else -> HealthLevel.INFO
            }
            val statusText = when {
                !def.enabled -> "已停用"
                st.lastError != null -> "失败 ${st.fails} 次：${st.lastError.take(60)}" + backoffNote(def, st.fails)
                st.lastOk > 0 -> "正常 · ${ago(now - st.lastOk)}前"
                else -> "等待首次检查"
            }
            SourceStatusRow(def.name, def.kind.wire, def.enabled, statusText, level)
        }.sortedBy { row ->
            when {
                row.level == HealthLevel.FAIL -> 0
                row.level == HealthLevel.WARN -> 1
                row.enabled -> 2
                else -> 3
            }
        }
        val ok = defs.count { c.sources.stateOf(it.name).let { s -> s.lastOk > 0 && s.lastError == null } }
        return Home(
            wantRun = c.settings.wantRun,
            running = isRunning(ctx),
            nextDueAt = next,
            nextTitle = nextTask?.title,
            nextTimeText = nextTask?.let { formatWhen(it.effectiveDueAt) },
            health = health,
            healthWorst = worst(health),
            sourcesOk = ok,
            sourcesTotal = defs.size,
            sourceStates = sourceStates,
        )
    }

    fun taskSections(ctx: Context): List<TaskSection> {
        val c = c(ctx)
        val now = c.clock.now()
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
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
                scheduleText = when (r.mode) {
                    ScheduleMode.TIMES ->
                        dev.watchhub.app.core.model.Recurrence.describeDays(r.recurrence.days) +
                            " " + dev.watchhub.app.core.model.Recurrence.describeTimes(r.recurrence.times)
                    ScheduleMode.INTERVAL -> "每 ${r.intervalMin} 分钟"
                    ScheduleMode.CRON -> "cron ${r.cron}"
                },
                nextText = nextTextFor(c, r.id, r.enabled),
                priorityText = priorityText(r.priority),
                enabled = r.enabled,
                calendarSync = r.calendarSync,
            )
        }
    }

    private fun nextTextFor(c: Container, reminderId: Long, enabled: Boolean): String {
        if (!enabled) return "已暂停"
        val latest = c.tasks.latestFor(reminderId) ?: return "待排期"
        if (latest.status != TaskStatus.OPEN) return "待排期"
        val at = if (latest.alertedAt == 0L) {
            latest.effectiveDueAt
        } else {
            latest.alertedAt + EscalationPolicy.intervalFor(latest.escalationLevel) * 60_000L
        }
        return "下次 " + formatWhen(at)
    }

    /**
     * 内置推荐订阅。只放国内直连可达、实测返回合法 RSS/Atom 的源
     * （2026-10-24 逐一 curl 验证；机器之心/36kr 的 RSS 已退化为 HTML，虎嗅超时，均排除）。
     * 海外源（OpenAI/DeepMind 等）在无代理网络下永久失败，不放推荐，仍可手动添加。
     */
    data class RecommendedFeed(val name: String, val url: String, val note: String)

    fun recommendedFeeds(): List<RecommendedFeed> = listOf(
        RecommendedFeed("量子位", "https://www.qbitai.com/feed", "中文 AI 资讯"),
        RecommendedFeed("少数派", "https://sspai.com/feed", "效率与数字生活 · 中文"),
        RecommendedFeed("IT之家", "https://www.ithome.com/rss", "科技快讯 · 中文"),
        RecommendedFeed("爱范儿", "https://www.ifanr.com/feed", "消费科技 · 中文"),
        RecommendedFeed("Solidot", "https://www.solidot.org/index.rss", "技术新闻 · 中文"),
    )

    fun reminderById(ctx: Context, id: Long): Reminder? = c(ctx).reminders.byId(id)

    fun sourceRows(ctx: Context): List<SourceRow> {
        val c = c(ctx)
        val now = c.clock.now()
        return c.sources.all().map { def ->
            val s = c.sources.stateOf(def.name)
            val (text, level) = when {
                !def.enabled -> "已停用" to HealthLevel.INFO
                s.lastError != null && s.fails > 0 ->
                    ("连续 ${s.fails} 次失败：${s.lastError.take(80)}" + backoffNote(def, s.fails)) to
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

    /** 源管理页只管高级监控源（json/regex/hash）；RSS 的增删改全部在订阅页。 */
    fun advancedSourceRows(ctx: Context): List<SourceRow> =
        sourceRows(ctx).filter { it.kind != SourceKind.RSS.wire }

    /** 「编辑全部」只装载高级源；RSS 源不进编辑器，保存时原样保留。 */
    fun rawAdvancedSourcesJson(ctx: Context): String {
        val arr = org.json.JSONArray()
        c(ctx).sources.all().filter { it.kind != SourceKind.RSS }.forEach { arr.put(it.raw) }
        return arr.toString(2)
    }

    /**
     * 保存「编辑全部」的结果：以编辑后的高级源为准整体替换；
     * 既有 RSS 源原样保留，编辑内容里同名/rss 条目按 upsert 语义覆盖。
     */
    fun saveAdvancedSourcesJson(ctx: Context, json: String): Result<Unit> = runCatching {
        val c = c(ctx)
        val edited = dev.watchhub.app.source.SourceDef.parseAll(json)
        val byName = LinkedHashMap<String, dev.watchhub.app.source.SourceDef>()
        c.sources.all().filter { it.kind == SourceKind.RSS }.forEach { byName[it.name] = it }
        edited.forEach { byName[it.name] = it }
        val merged = org.json.JSONArray()
        byName.values.forEach { merged.put(it.raw) }
        c.sources.replaceAll(merged.toString())
        reconcileAsync(ctx)
    }.recoverCatching { t ->
        throw IllegalArgumentException(t.message ?: "保存失败")
    }

    /** 订阅源列表（kind=rss）。 */
    fun subscriptions(ctx: Context): List<SubscriptionRow> {
        val c = c(ctx)
        val now = c.clock.now()
        return c.sources.all().filter { it.kind == SourceKind.RSS }.map { def ->
            val state = c.sources.stateOf(def.name)
            SubscriptionRow(
                name = def.name,
                unread = c.articles.unreadCount(def.name),
                enabled = def.enabled,
                statusText = when {
                    !def.enabled -> "已暂停"
                    state.lastError != null -> "抓取失败：${state.lastError.take(60)}" + backoffNote(def, state.fails)
                    state.lastOk > 0 -> "更新于 ${ago(now - state.lastOk)}前"
                    else -> "等待首次抓取"
                },
            )
        }
    }

    /** 文章列表，可按订阅源过滤 / 只看未读。 */
    fun articles(
        ctx: Context,
        source: String? = null,
        unreadOnly: Boolean = false,
        limit: Int = 200,
    ): List<ArticleRow> {
        val c = c(ctx)
        return c.articles.recent(limit, source, unreadOnly).map { a ->
            ArticleRow(
                id = a.id,
                title = a.title,
                source = a.source,
                url = a.url,
                summary = a.summary,
                timeText = formatWhen(if (a.publishedAt > 0) a.publishedAt else a.fetchedAt),
                unread = a.readAt == 0L,
            )
        }
    }

    fun unreadArticleCount(ctx: Context): Int = c(ctx).articles.unreadCount()

    fun articleById(ctx: Context, id: Long): Article? = c(ctx).articles.byId(id)

    fun markArticleRead(ctx: Context, id: Long) {
        val c = c(ctx)
        c.articles.markRead(id, c.clock.now())
    }

    fun markAllArticlesRead(ctx: Context, source: String? = null): Int {
        val c = c(ctx)
        val n = c.articles.markAllRead(source, c.clock.now())
        LogBus.post("全部标为已读：$source".replace("：null", "（全部）"))
        return n
    }

    /**
     * 添加 RSS 订阅：用户填网页或 feed 地址，自动发现 feed 并落库。
     *
     * 阻塞网络调用，必须在后台线程调用。返回生成的源名。
     */
    fun addRssSubscription(ctx: Context, rawUrl: String): Result<String> {
        return runCatching {
            val c = c(ctx)
            val url = rawUrl.trim()
            require(url.startsWith("http://") || url.startsWith("https://")) {
                "地址必须以 http:// 或 https:// 开头"
            }
            val feeds = FeedDiscovery.discover(url)
            require(feeds.isNotEmpty()) {
                "这个页面没有找到 RSS/Atom 订阅源；如果知道 feed 直链，可以直接填它"
            }
            val feedUrl = feeds.first()
            val existing = c.sources.all().map { it.name }.toSet()
            val base = Text.slug(feedUrl)
            var name = base
            var i = 2
            while (name in existing) name = "$base-${i++}"
            val json = org.json.JSONObject().apply {
                put("name", name)
                put("kind", "rss")
                put("url", feedUrl)
                put("interval", 900)
                put("limit", 30)
                put("enabled", true)
                put("priority", "normal")
                put("tags", org.json.JSONArray().put("rss"))
            }
            val def = dev.watchhub.app.source.SourceDef.parse(json)
                ?: throw IllegalArgumentException("生成的订阅配置不合法")
            c.sources.upsert(def)
            LogBus.post("新增订阅 $name → $feedUrl")
            // 立即拉一次，订阅页马上有内容（失败也不影响订阅本身）
            runCatching { c.poller.checkNow(name) }
            reconcileAsync(ctx)
            name
        }.recoverCatching { t ->
            throw IllegalArgumentException(t.message ?: "添加订阅失败")
        }
    }

    /** 只发现不添加，用于 UI 预览候选 feed。阻塞网络调用。 */
    fun discoverFeeds(url: String): List<String> = FeedDiscovery.discover(url)

    /**
     * 手动刷新所有启用的 RSS 订阅（阻塞网络，必须在后台线程调用）。
     * 返回新入库的文章条数。
     */
    fun refreshSubscriptions(ctx: Context): Int {
        val c = c(ctx)
        val before = c.articles.totalCount()
        c.sources.all()
            .filter { it.kind == SourceKind.RSS && it.enabled }
            .forEach { def -> runCatching { c.poller.checkNow(def.name) } }
        return (c.articles.totalCount() - before).coerceAtLeast(0)
    }

    fun taskById(ctx: Context, id: Long): Task? = c(ctx).tasks.byId(id)

    /** 5 段 cron 表达式是否合法（供编辑页保存前校验）。 */
    fun isValidCron(expr: String): Boolean = CronExpression.parse(expr) != null

    /** cron 表达式下一次触发的展示文案；非法表达式返回 null。 */
    fun cronNextText(ctx: Context, expr: String): String? {
        val now = c(ctx).clock.now()
        val next = CronExpression.parse(expr)?.nextAfter(now, java.time.ZoneId.systemDefault())
            ?: return null
        return formatWhen(next)
    }

    fun recentAlerts(ctx: Context, limit: Int = 100): List<Alert> = c(ctx).alerts.recent(limit)

    fun alertById(ctx: Context, id: Long): Alert? = c(ctx).alerts.byId(id)

    fun logs(): List<String> = LogBus.tail(300)

    fun clearLogs() = LogBus.clear()

    /**
     * 体检结果缓存 30 秒。
     *
     * 首页 15 秒刷新一次，但体检里的 PackageManager / AlarmManager 查询没必要同频；
     * 缓存只在进程内，时间通过 Clock 注入，可单测（见 TtlCacheTest）。
     */
    private val healthCache = TtlCache<List<HealthItem>>(ttlMs = 30_000L)

    fun health(ctx: Context): List<HealthItem> {
        val c = c(ctx)
        return healthCache.get(c.clock.now()) {
            Health(c.ctx, c.settings, c.alerts, c.sources, c.clock).check()
        }
    }

    /** 体检页手动刷新时用。 */
    fun invalidateHealthCache() = healthCache.invalidate()

    fun settings(ctx: Context): SettingsSnapshot {
        val c = c(ctx)
        return SettingsSnapshot(
            startOnBoot = c.settings.startOnBoot,
            calendarEnabled = c.settings.calendarEnabled,
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
        // 改了调度/时间后，旧的未来任务必须按新配置重排
        c.tasks.deleteFutureOpen(id, c.clock.now())
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
        HealthAction.FULL_SCREEN_INTENT -> {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                open(
                    ctx,
                    Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(ctx)),
                )
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
    fun checkUpdate(): UpdateCheck = Updater.check()

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

    /** 连续失败进入退避时的用户提示；间隔计算全部在 Backoff（纯函数，有单测）。 */
    private fun backoffNote(def: SourceDef, fails: Int): String =
        if (fails >= Backoff.FAIL_THRESHOLD) {
            " · 已降频至每 " + Backoff.intervalText(
                Backoff.effectiveIntervalSec(def.intervalSec, fails),
            )
        } else {
            ""
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
