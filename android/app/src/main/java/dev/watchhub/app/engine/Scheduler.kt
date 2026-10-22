package dev.watchhub.app.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.watchhub.app.core.Clock
import dev.watchhub.app.core.LogBus
import dev.watchhub.app.service.AlarmReceiver

/**
 * 任务到期的精确闹钟。
 *
 * 前台服务只负责「源轮询」这种分钟级、非硬实时的活；真正的提醒时刻由 AlarmManager 负责，
 * 这样即使服务被厂商冻结，闹钟也能把进程叫起来。
 *
 * Android 12+ 需要 SCHEDULE_EXACT_ALARM 授权；没有授权时降级成非精确，
 * 并在体检页显式告警（不静默降级）。
 */
class Scheduler(private val ctx: Context, private val clock: Clock) {

    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        } else true

    /** 排下一次到期闹钟；传 null 表示当前没有待触发任务，取消闹钟。 */
    fun armAt(atMillis: Long?) {
        if (atMillis == null) {
            cancel()
            return
        }
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent()
        val at = atMillis.coerceAtLeast(clock.now() + 1_000L)
        try {
            if (canScheduleExact()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                LogBus.post("精确闹钟未授权，降级为非精确（体检页可查看）")
            }
        } catch (t: SecurityException) {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
            LogBus.post("闹钟设置失败，已降级: ${t.message}")
        }
    }

    fun cancel() {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent())
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        REQUEST_CODE,
        Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_FIRE = "dev.watchhub.app.ALARM_FIRE"
        private const val REQUEST_CODE = 1001
    }
}
