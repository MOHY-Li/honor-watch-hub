package dev.watchhub.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.watchhub.app.App
import dev.watchhub.app.core.LogBus

/**
 * 开机 / 本应用升级后对齐。
 *
 * 注意：升级（MY_PACKAGE_REPLACED）会杀掉进程并清掉闹钟，所以这个 receiver 也必须处理，
 * 否则用户升级一次之后所有任务提醒就永久失效了。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val app = context.applicationContext
        val pending = goAsync()
        Thread({
            try {
                val c = App.container(app)
                c.reconciler.run(if (intent.action == Intent.ACTION_BOOT_COMPLETED) "boot" else "upgrade")
                if (c.settings.startOnBoot && c.settings.wantRun) {
                    runCatching { HubService.start(app) }
                        .onFailure { LogBus.post("开机启动中枢失败: ${it.message}") }
                }
                WatchdogWorker.enqueue(app)
            } catch (t: Throwable) {
                LogBus.post("开机对齐失败: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                pending.finish()
            }
        }, "watchhub-boot").start()
    }
}
