package dev.watchhub.app.delivery

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.watchhub.app.R
import dev.watchhub.app.core.Text
import dev.watchhub.app.core.model.Alert
import dev.watchhub.app.core.model.Priority
import dev.watchhub.app.service.AlertActionReceiver
import dev.watchhub.app.ui.MainActivity
import dev.watchhub.app.ui.ReminderNudgeActivity

/**
 * 唯一通知出口。
 *
 * 这是整条链路能不能上手表的关键：荣耀运动健康镜像的是手机通知栏，
 * 所以这里只能发最朴素的 title + text（约束见 AGENTS.md §5）。
 * 任何自定义 RemoteViews / 大图 / 按钮都会被镜像丢掉。
 */
object Notifier {

    const val CHANNEL_ALERTS = "watchhub_alerts"
    const val CHANNEL_SERVICE = "watchhub_service"
    private const val TEST_ID = 990001

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "WatchHub 提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "任务到期与网站/番剧更新，会镜像到手表"
                enableVibration(true)
                setShowBadge(true)
                // 绝不能 setSilent(true)：静默渠道在手表上也不震
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "WatchHub 运行状态", NotificationManager.IMPORTANCE_MIN).apply {
                description = "前台服务的静默常驻通知，不会被镜像"
                setShowBadge(false)
            }
        )
    }

    fun canPost(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED && NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        } else {
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        }

    fun channelImportance(ctx: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return NotificationManager.IMPORTANCE_HIGH
        val nm = ctx.getSystemService(NotificationManager::class.java)
        return nm.getNotificationChannel(CHANNEL_ALERTS)?.importance ?: NotificationManager.IMPORTANCE_NONE
    }

    /**
     * 发一条提醒。没有通知权限时抛异常，由 Deliverer 记为失败（体检页会显示）。
     *
     * lint 的 MissingPermission 看不到 [canPost] 里的运行时检查，这里显式压制；
     * 权限确实缺失时上面已经抛了 IllegalStateException。
     */
    @SuppressLint("MissingPermission")
    fun show(ctx: Context, alert: Alert) {
        ensureChannels(ctx)
        if (!canPost(ctx)) throw IllegalStateException("通知权限未授予或通知被关闭")

        val open = PendingIntent.getActivity(
            ctx,
            alert.id.toInt().coerceAtLeast(0),
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_ALERT_ID, alert.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Text.title(alert.title))
            .setContentText(Text.body(alert.body.ifBlank { alert.source.ifBlank { "WatchHub" } }))
            .setStyle(NotificationCompat.BigTextStyle().bigText(Text.body(alert.body)))
            .setPriority(
                if (alert.priority == Priority.HIGH) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setCategory(
                if (alert.priority == Priority.HIGH) NotificationCompat.CATEGORY_ALARM
                else NotificationCompat.CATEGORY_MESSAGE
            )
            .setAutoCancel(true)
            .setContentIntent(open)
            .setWhen(if (alert.at > 0) alert.at else System.currentTimeMillis())
            .setShowWhen(true)

        // 提醒类通知：手机端可直接处理；手表镜像只会显示标题+正文，操作按钮不会上表
        alert.taskId?.let { taskId ->
            val tag = tagOf(alert)
            builder.addAction(0, "完成", actionIntent(ctx, alert, tag, taskId, AlertActionReceiver.ACTION_COMPLETE, 1))
            builder.addAction(0, "稍后 10 分钟", actionIntent(ctx, alert, tag, taskId, AlertActionReceiver.ACTION_SNOOZE, 2))
            val nudge = Intent(ctx, ReminderNudgeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(ReminderNudgeActivity.EXTRA_TASK_ID, taskId)
            }
            val fullScreen = PendingIntent.getActivity(
                ctx,
                alert.id.toInt().coerceAtLeast(1) + 10_000,
                nudge,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // 没有全屏意图权限时（Android 14+ 可能被收回），系统会退化为高优先级 heads-up
            builder.setFullScreenIntent(fullScreen, true)
        }

        NotificationManagerCompat.from(ctx).notify(tagOf(alert), alert.id.toInt(), builder.build())
    }

    /** 服务常驻通知（MIN 渠道）。 */
    fun serviceNotification(ctx: Context, text: String) =
        NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("WatchHub")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(
                PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    /**
     * 体检用：确认一条测试通知真的出现在通知栏里（证明手机侧最前面几环是通的）。
     * 返回是否读到。
     */
    fun testVisible(ctx: Context): Boolean {
        if (!canPost(ctx)) return false
        val nm = ctx.getSystemService(NotificationManager::class.java)
        return nm.activeNotifications.any { it.id == TEST_ID }
    }

    @SuppressLint("MissingPermission")
    fun postTest(ctx: Context) {
        ensureChannels(ctx)
        if (!canPost(ctx)) throw IllegalStateException("通知权限未授予")
        val open = PendingIntent.getActivity(
            ctx, TEST_ID, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("WatchHub 测试")
            .setContentText("收到这条说明手机侧通路正常；手表没震请看体检页")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .build()
        NotificationManagerCompat.from(ctx).notify("wh:test", TEST_ID, n)
    }

    private fun actionIntent(
        ctx: Context,
        alert: Alert,
        tag: String,
        taskId: Long,
        action: String,
        slot: Int,
    ): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        alert.id.toInt() * 10 + slot,
        Intent(ctx, AlertActionReceiver::class.java).apply {
            this.action = action
            putExtra(AlertActionReceiver.EXTRA_TASK_ID, taskId)
            putExtra(AlertActionReceiver.EXTRA_TAG, tag)
            putExtra(AlertActionReceiver.EXTRA_ID, alert.id.toInt())
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 同一来源/同一提醒用固定 tag 覆盖，避免连续更新把通知栏刷满。 */
    private fun tagOf(alert: Alert): String =
        "wh:${alert.kind.name.lowercase()}:${alert.source.ifBlank { "manual" }}"
}
