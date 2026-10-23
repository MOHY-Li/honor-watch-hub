package dev.watchhub.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import dev.watchhub.app.App
import dev.watchhub.app.core.LogBus

/**
 * 通知操作按钮的落点：完成 / 稍后。
 *
 * 手表镜像不会带上通知按钮，所以这些动作只能在手机上点；
 * 点完会取消对应通知并跑一次对齐，让下一次提醒/闹钟尽快排上。
 */
class AlertActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId <= 0L) return
        val app = context.applicationContext
        val container = App.container(app)
        when (intent.action) {
            ACTION_COMPLETE -> container.engine.complete(taskId)
            ACTION_SNOOZE -> container.engine.snooze(taskId, 10)
            else -> return
        }
        val tag = intent.getStringExtra(EXTRA_TAG)
        val id = intent.getIntExtra(EXTRA_ID, 0)
        if (!tag.isNullOrBlank() && id != 0) {
            runCatching { NotificationManagerCompat.from(app).cancel(tag, id) }
        }
        runCatching { container.reconciler.run("alert-action") }
            .onFailure { LogBus.post("通知操作后对齐失败: ${it.message}") }
    }

    companion object {
        const val ACTION_COMPLETE = "dev.watchhub.app.ALERT_COMPLETE"
        const val ACTION_SNOOZE = "dev.watchhub.app.ALERT_SNOOZE"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TAG = "notif_tag"
        const val EXTRA_ID = "notif_id"
    }
}
