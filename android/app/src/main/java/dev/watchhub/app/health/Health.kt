package dev.watchhub.app.health

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import dev.watchhub.app.core.Clock
import dev.watchhub.app.core.NotificationHealthPolicy
import dev.watchhub.app.data.AlertStore
import dev.watchhub.app.data.SettingsStore
import dev.watchhub.app.data.SourceStore
import dev.watchhub.app.delivery.Notifier

enum class HealthLevel { OK, WARN, FAIL, INFO }

/** 体检项点一下要做什么。UI 负责把 action 变成 Activity/权限请求。 */
enum class HealthAction {
    NOTIFICATION_PERMISSION,
    CHANNEL_SETTINGS,
    APP_NOTIFICATION_SETTINGS,
    EXACT_ALARM,
    BATTERY_OPTIMIZATION,
    START_SERVICE,
    OPEN_APP_SETTINGS,
    CALENDAR_PERMISSION,
    MIRROR_SETTINGS,
    FULL_SCREEN_INTENT,
}

data class HealthItem(
    val id: String,
    val title: String,
    val detail: String,
    val level: HealthLevel,
    val action: HealthAction? = null,
)

/**
 * 链路体检。
 *
 * 通知镜像是一条四段串联的链路：App 通知权限 → 渠道重要性 → 运动健康镜像 → 手表。
 * 任何一段断了都是「完全没声音」，而且前三段在 App 内可以低成本自检。
 * 这就是 v1 只有一个「测试通知」按钮、v2 要补的东西。
 */
class Health(
    private val ctx: Context,
    private val settings: SettingsStore,
    private val alerts: AlertStore,
    private val sources: SourceStore,
    private val clock: Clock,
) {

    fun check(): List<HealthItem> {
        val out = ArrayList<HealthItem>()
        out.add(notificationPermission())
        out.add(channelImportance())
        out.add(fullScreenIntent())
        out.add(exactAlarm())
        out.add(battery())
        out.add(serviceHeartbeat())
        out.add(mirrorApp())
        out.add(mirrorConfirmed())
        out.add(lastDelivery())
        addAlerts(out)
        addSources(out)
        addCalendar(out)
        out.add(watchLimit())
        return out
    }

    /** 找荣耀运动健康。找不到说明用户没装（或者 ROM 改了包名/标签）。 */
    fun mirrorApp(): HealthItem {
        val found = findMirrorApp()
        return if (found != null) {
            HealthItem(
                id = "mirror_app",
                title = "荣耀运动健康",
                detail = "已安装：${found.second}",
                level = HealthLevel.OK,
                action = HealthAction.MIRROR_SETTINGS,
            )
        } else {
            HealthItem(
                id = "mirror_app",
                title = "荣耀运动健康",
                detail = "没在桌面找到类似 App。若已安装可忽略；没有它就无法把通知镜像到手表。",
                level = HealthLevel.WARN,
            )
        }
    }

    fun findMirrorApp(): Pair<String, String>? {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        val hit = resolved.firstOrNull { info ->
            val pkg = info.activityInfo.packageName
            val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault("")
            label.contains("运动健康") ||
                pkg.contains("hihonor.health") ||
                pkg.contains("huawei.health") ||
                pkg.contains("honor.health")
        } ?: return null
        val label = runCatching { hit.loadLabel(pm).toString() }.getOrDefault(hit.activityInfo.packageName)
        return hit.activityInfo.packageName to label
    }

    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        } else true

    private fun notificationPermission(): HealthItem {
        val enabled = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        return if (enabled) {
            HealthItem("notification", "通知权限", "已开启", HealthLevel.OK)
        } else {
            HealthItem(
                "notification", "通知权限", "被关闭。没有它，任何提醒都到不了手表。",
                HealthLevel.FAIL, HealthAction.NOTIFICATION_PERMISSION,
            )
        }
    }

    private fun channelImportance(): HealthItem {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return HealthItem("channel", "提醒渠道", "系统版本较低，使用默认渠道", HealthLevel.OK)
        }
        return when (NotificationHealthPolicy.classify(Notifier.channelImportance(ctx))) {
            NotificationHealthPolicy.ChannelState.OFF -> HealthItem(
                "channel", "提醒渠道", "渠道被关闭了，通知发不出去。点进去重新打开。",
                HealthLevel.FAIL, HealthAction.CHANNEL_SETTINGS,
            )
            NotificationHealthPolicy.ChannelState.LOW -> HealthItem(
                "channel", "提醒渠道",
                "当前为「低」，手机上不会响也不会震。能调高就调高；系统没有更高选项时，以「测试通知」是否到手为准。",
                HealthLevel.WARN, HealthAction.CHANNEL_SETTINGS,
            )
            NotificationHealthPolicy.ChannelState.DEFAULT -> HealthItem(
                "channel", "提醒渠道",
                "系统只提供「默认」级别（荣耀机型常见），不影响手表镜像；以「测试通知」是否到手为准。",
                HealthLevel.OK,
            )
            NotificationHealthPolicy.ChannelState.HIGH -> HealthItem(
                "channel", "提醒渠道", "高，可以响铃震动。", HealthLevel.OK,
            )
        }
    }

    private fun fullScreenIntent(): HealthItem {
        if (Build.VERSION.SDK_INT < 34) {
            return HealthItem("fullscreen", "确认窗口", "系统版本不需要额外权限", HealthLevel.OK)
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        return if (nm.canUseFullScreenIntent()) {
            HealthItem("fullscreen", "确认窗口", "已允许锁屏弹出确认窗口", HealthLevel.OK)
        } else {
            HealthItem(
                "fullscreen", "确认窗口",
                "全屏确认窗口权限未授予，锁屏时可能只显示通知。点进去允许「全屏通知」。",
                HealthLevel.WARN, HealthAction.FULL_SCREEN_INTENT,
            )
        }
    }

    private fun exactAlarm(): HealthItem =
        if (canScheduleExact()) {
            HealthItem("exact_alarm", "精确闹钟", "已授权，任务可以准点触发", HealthLevel.OK)
        } else {
            HealthItem(
                "exact_alarm", "精确闹钟", "未授权。任务会延迟触发（可能晚几分钟到几十分钟）。",
                HealthLevel.WARN, HealthAction.EXACT_ALARM,
            )
        }

    private fun battery(): HealthItem {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        return if (ignoring) {
            HealthItem("battery", "电池优化", "已加入白名单", HealthLevel.OK)
        } else {
            HealthItem(
                "battery", "电池优化", "系统可能会冻结后台。建议对本应用关闭电池优化。",
                HealthLevel.WARN, HealthAction.BATTERY_OPTIMIZATION,
            )
        }
    }

    private fun serviceHeartbeat(): HealthItem {
        val now = clock.now()
        val hb = settings.heartbeatAt
        val age = if (hb == 0L) -1L else now - hb
        return when {
            !settings.wantRun -> HealthItem(
                "service", "中枢服务", "未启动（点首页「启动中枢」）",
                HealthLevel.WARN, HealthAction.START_SERVICE,
            )
            age < 0 || age > STALE_MS -> HealthItem(
                "service", "中枢服务", "心跳已断（${if (age < 0) "从未启动" else "${age / 1000}s 未更新"}），可能被系统杀了",
                HealthLevel.FAIL, HealthAction.START_SERVICE,
            )
            else -> HealthItem("service", "中枢服务", "运行中，心跳 ${age / 1000}s 前", HealthLevel.OK)
        }
    }

    private fun mirrorConfirmed(): HealthItem =
        if (settings.mirrorConfirmed) {
            HealthItem(
                "mirror_confirmed", "手表镜像开关",
                "已确认在运动健康里勾选 WatchHub",
                HealthLevel.OK,
            )
        } else {
            HealthItem(
                "mirror_confirmed", "手表镜像开关",
                "还没确认。运动健康 → 设备 → 消息通知 → 勾选 WatchHub（只能手动做）",
                HealthLevel.INFO,
            )
        }

    private fun lastDelivery(): HealthItem {
        val at = settings.lastDeliveredAt
        if (at <= 0) {
            return HealthItem("last_delivery", "最近一次推送", "还没有推送记录", HealthLevel.INFO)
        }
        val mins = (clock.now() - at) / 60_000
        return HealthItem("last_delivery", "最近一次推送", "${mins} 分钟前", HealthLevel.OK)
    }

    private fun addAlerts(out: MutableList<HealthItem>) {
        val failed = alerts.failedCount()
        val pending = alerts.pendingCount()
        if (failed > 0) {
            out.add(
                HealthItem(
                    "alerts", "送达失败",
                    "$failed 条提醒重试 5 次仍失败，可能通知权限被撤",
                    HealthLevel.WARN,
                )
            )
        } else if (pending > 0) {
            out.add(HealthItem("alerts", "待送达", "$pending 条在 outbox 里等待补发", HealthLevel.INFO))
        }
    }

    private fun addSources(out: MutableList<HealthItem>) {
        val failing = sources.failingSources(3)
        if (failing.isNotEmpty()) {
            out.add(
                HealthItem(
                    "sources", "数据源异常",
                    failing.take(3).joinToString("、") { "${it.name}（连续 ${it.fails} 次失败）" },
                    HealthLevel.WARN,
                )
            )
        }
    }

    private fun addCalendar(out: MutableList<HealthItem>) {
        if (!settings.calendarEnabled) return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.WRITE_CALENDAR,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            out.add(
                HealthItem(
                    "calendar", "日历兜底", "已开启但缺少日历权限", HealthLevel.WARN, HealthAction.CALENDAR_PERMISSION,
                )
            )
        } else {
            out.add(HealthItem("calendar", "日历兜底", "已开启，高优先级任务会同时写日历", HealthLevel.OK))
        }
    }

    private fun watchLimit(): HealthItem =
        HealthItem(
            "watch_limit", "手表端限制",
            "镜像只显示「标题 + 一行正文」，不能打勾/回复。完成和稍后只能在手机上操作。",
            HealthLevel.INFO,
        )

    companion object {
        const val STALE_MS = 5 * 60_000L
    }
}
