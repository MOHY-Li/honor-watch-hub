package dev.watchhub.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.watchhub.app.App
import dev.watchhub.app.core.LogBus
import dev.watchhub.app.engine.Scheduler

/**
 * 精确闹钟的落点。
 *
 * 为什么用 goAsync + 短线程而不是直接 startForegroundService：
 * Android 12+ 对后台启动前台服务有严格限制，闹钟广播里唯一可靠能做的是
 * 「短时间同步干活」——正好我们要做的事（查库、发通知、排下一次）都是毫秒级。
 * 干完活后再尝试把常驻服务拉起来，失败也不影响本次提醒。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Scheduler.ACTION_FIRE) return
        val app = context.applicationContext
        val pending = goAsync()
        Thread({
            try {
                App.container(app).reconciler.run("alarm")
                if (App.container(app).settings.wantRun) {
                    runCatching { HubService.start(app) }
                }
            } catch (t: Throwable) {
                LogBus.post("闹钟处理失败: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                pending.finish()
            }
        }, "watchhub-alarm").start()
    }
}
