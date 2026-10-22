package dev.watchhub.app.service

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.watchhub.app.App
import dev.watchhub.app.core.LogBus
import java.util.concurrent.TimeUnit

/**
 * 看门狗。
 *
 * 荣耀/华为的系统杀后台非常激进，前台服务也可能被冻结。这个 Worker 每 15 分钟醒来一次：
 *  - 心跳超过 5 分钟没更新 → 认为中枢已死，重新拉起服务
 *  - 无论死活都跑一次 reconcile（补任务、补发 outbox、重排闹钟）
 *
 * WorkManager 的最低周期就是 15 分钟，所以它只是兜底，不是主调度。
 */
class WatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        val app = applicationContext
        val c = App.container(app)
        val now = c.clock.now()
        val heartbeat = c.settings.heartbeatAt
        val stale = c.settings.wantRun && (heartbeat == 0L || now - heartbeat > STALE_MS)
        if (stale) {
            LogBus.post("看门狗：心跳已 ${(now - heartbeat) / 1000}s 未更新，重启中枢")
            runCatching { HubService.start(app) }
                .onFailure { LogBus.post("看门狗拉起服务失败: ${it.message}") }
        }
        runCatching { c.reconciler.run("watchdog") }
            .onFailure { LogBus.post("看门狗对齐失败: ${it.message}") }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "watchhub_watchdog"
        const val STALE_MS = 5 * 60_000L

        fun enqueue(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            runCatching {
                WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request,
                )
            }.onFailure { LogBus.post("看门狗排程失败: ${it.message}") }
        }
    }
}
