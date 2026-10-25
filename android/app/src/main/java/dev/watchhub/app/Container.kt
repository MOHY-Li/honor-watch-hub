package dev.watchhub.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.watchhub.app.core.ChangeBus
import dev.watchhub.app.core.Clock
import dev.watchhub.app.core.WallClock
import dev.watchhub.app.data.AlertStore
import dev.watchhub.app.data.ArticleStore
import dev.watchhub.app.data.Db
import dev.watchhub.app.data.HealthReportStore
import dev.watchhub.app.data.HealthRuleStore
import dev.watchhub.app.data.MetricStore
import dev.watchhub.app.data.ReminderStore
import dev.watchhub.app.data.SeenStore
import dev.watchhub.app.data.SettingsStore
import dev.watchhub.app.data.SourceStore
import dev.watchhub.app.data.TaskStore
import dev.watchhub.app.delivery.CalendarSync
import dev.watchhub.app.delivery.Deliverer
import dev.watchhub.app.engine.HealthAnalyst
import dev.watchhub.app.engine.HealthEngine
import dev.watchhub.app.engine.Migrator
import dev.watchhub.app.engine.Poller
import dev.watchhub.app.engine.Reconciler
import dev.watchhub.app.engine.ReminderEngine
import dev.watchhub.app.engine.Scheduler
import dev.watchhub.app.engine.UnavailableHealthReader

/**
 * 进程级依赖容器。所有组件共享同一个 Db / Clock，UI 和前台服务看到的是同一份状态。
 */
class Container(context: Context) {

    val ctx: Context = context.applicationContext
    val clock: Clock = WallClock

    /** UI 变更总线：写操作后通知已打开的页面即时刷新（监听者在主线程回调）。 */
    val changes = ChangeBus { r -> Handler(Looper.getMainLooper()).post(r) }

    val db = Db(ctx)
    val settings = SettingsStore(db)
    val alerts = AlertStore(db)
    val articles = ArticleStore(db)
    val seen = SeenStore(db)
    val sources = SourceStore(db)
    val reminders = ReminderStore(db)
    val tasks = TaskStore(db)

    val poller = Poller(sources, alerts, articles, seen, clock)
    val engine = ReminderEngine(reminders, tasks, alerts, clock)
    val metricStore = MetricStore(db)
    val healthRules = HealthRuleStore(db)
    val healthReports = HealthReportStore(db)
    // 读取端在荣耀 Health Kit 资格过审后替换为真实实现（见 HealthMetricsReader）
    val health = HealthEngine(UnavailableHealthReader, metricStore, healthRules, alerts, clock)
    val analyst = HealthAnalyst(metricStore, alerts, healthReports, settings, clock)
    val deliverer = Deliverer(ctx, alerts, settings, clock)
    val scheduler = Scheduler(ctx, clock)
    val calendar = CalendarSync(ctx, tasks, settings, clock)
    val migrator = Migrator(ctx, settings, sources, reminders, clock)

    val reconciler = Reconciler(migrator, engine, deliverer, scheduler, calendar, settings, clock)
}
