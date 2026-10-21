package dev.watchhub.app

import android.Manifest
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

/**
 * 把 Notif 变成系统通知。
 *
 * **这是整套方案能到手表上的关键一步**：
 * 荣耀手表（Watch 5 Pro）本身不跑第三方 App，但「荣耀运动健康 → 设备 → 消息通知」
 * 会把手机上指定 App 的通知镜像到手表。所以我们只要让 WatchHub 发出普通系统通知，
 * 用户在运动健康里勾选 WatchHub，手表就会震动并显示。
 *
 * 因此约束很重要（已在服务端裁剪，这里再兜底）：
 *  - title/body 必须短，太长手表会截断成两行甚至不显示
 *  - 不要用自定义 RemoteViews / 大图 / 按钮，镜像会丢
 *  - 用「独立渠道 + IMPORTANCE_HIGH」，用户可在系统里单独设成重要
 */
object Notifier {

    const val CHANNEL_ALERTS = "watchhub_alerts"
    const val CHANNEL_SERVICE = "watchhub_service"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "WatchHub 推送", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "网站/番剧更新与定时提醒，会镜像到手表"
                enableVibration(true)
                setShowBadge(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "WatchHub 连接", NotificationManager.IMPORTANCE_MIN).apply {
                description = "保持与服务端的长连接，静默"
                setShowBadge(false)
            }
        )
    }

    fun canPost(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        else NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun show(ctx: Context, n: Notif) {
        ensureChannels(ctx)
        if (!canPost(ctx)) return

        val open = PendingIntent.getActivity(
            ctx, n.id.toInt().coerceAtLeast(0),
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("notif_id", n.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = clip(n.title, 40)
        val body = clip(n.body.ifBlank { n.source.ifBlank { "WatchHub" } }, 120)

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (n.isHigh) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (n.isHigh) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)

        // 同一 source+title 用同一个 tag，避免手表上刷屏；但每条仍会重新提醒
        val tag = "wh:" + n.source.ifBlank { "manual" }
        try {
            NotificationManagerCompat.from(ctx).notify(tag, n.id.toInt(), builder.build())
        } catch (_: SecurityException) {
            // 权限被撤销
        }
    }

    /** 服务的前台常驻通知（渠道为 MIN，不会镜像到手表，避免打扰）。 */
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

    private fun clip(s: String, n: Int): String =
        if (s.length <= n) s else s.substring(0, n - 1) + "…"
}
