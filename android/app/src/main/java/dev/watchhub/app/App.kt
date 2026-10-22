package dev.watchhub.app

import android.app.Application
import android.content.Context
import dev.watchhub.app.core.LogBus
import dev.watchhub.app.delivery.Notifier
import dev.watchhub.app.service.HubService
import dev.watchhub.app.service.WatchdogWorker

/**
 * 进程入口。
 *
 * 三件事：建通知渠道、排看门狗、后台做一次对齐（补发 + 排闹钟）。
 * 不在这里启动前台服务：用户没点「启动中枢」时不应该看到常驻通知；
 * 服务只由用户操作、闹钟、开机、看门狗拉起。
 */
class App : Application() {

    val container: Container by lazy { Container(this) }

    override fun onCreate() {
        super.onCreate()
        val c = container
        Notifier.ensureChannels(this)
        WatchdogWorker.enqueue(this)

        Thread({
            runCatching { c.reconciler.run("app-start") }
                .onFailure { LogBus.post("启动对齐失败: ${it.message}") }
            if (c.settings.wantRun) {
                runCatching { HubService.start(this) }
                    .onFailure { LogBus.post("启动中枢失败: ${it.message}") }
            }
        }, "watchhub-startup").start()
    }

    companion object {
        fun container(ctx: Context): Container = (ctx.applicationContext as App).container
    }
}
