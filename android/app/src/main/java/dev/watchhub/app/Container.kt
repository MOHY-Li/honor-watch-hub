package dev.watchhub.app

import android.content.Context
import dev.watchhub.app.core.Clock
import dev.watchhub.app.core.WallClock
import dev.watchhub.app.data.AlertStore
import dev.watchhub.app.data.ArticleStore
import dev.watchhub.app.data.Db
import dev.watchhub.app.data.ReminderStore
import dev.watchhub.app.data.SeenStore
import dev.watchhub.app.data.SettingsStore
import dev.watchhub.app.data.SourceStore
import dev.watchhub.app.data.TaskStore
import dev.watchhub.app.delivery.CalendarSync
import dev.watchhub.app.delivery.Deliverer
import dev.watchhub.app.engine.Migrator
import dev.watchhub.app.engine.Poller
import dev.watchhub.app.engine.Reconciler
import dev.watchhub.app.engine.ReminderEngine
import dev.watchhub.app.engine.Scheduler

/**
 * 进程级依赖容器。所有组件共享同一个 Db / Clock，UI 和前台服务看到的是同一份状态。
 */
class Container(context: Context) {

    val ctx: Context = context.applicationContext
    val clock: Clock = WallClock

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
    val deliverer = Deliverer(ctx, alerts, settings, clock)
    val scheduler = Scheduler(ctx, clock)
    val calendar = CalendarSync(ctx, tasks, settings, clock)
    val migrator = Migrator(ctx, settings, sources, reminders, clock)

    val reconciler = Reconciler(migrator, engine, deliverer, scheduler, calendar, settings, clock)
}
