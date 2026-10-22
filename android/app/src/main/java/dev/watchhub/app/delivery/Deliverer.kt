package dev.watchhub.app.delivery

import android.content.Context
import dev.watchhub.app.core.Clock
import dev.watchhub.app.core.LogBus
import dev.watchhub.app.data.AlertStore
import dev.watchhub.app.data.SettingsStore

/**
 * outbox 派发器。可靠性契约的执行者：
 *
 *   1. alert 已经在 SQLite 里是 PENDING（Poller / ReminderEngine 落库）
 *   2. 这里逐条发通知，成功才标 DELIVERED
 *   3. 失败累加重试次数（最多 5 次），体检页能看到 FAILED
 *
 * 所以进程在「落库」和「发通知」之间被杀，下次启动会补发。
 */
class Deliverer(
    private val ctx: Context,
    private val alerts: AlertStore,
    private val settings: SettingsStore,
    private val clock: Clock,
) {

    /** 派发所有待送达。返回成功条数。 */
    fun dispatchPending(): Int {
        var sent = 0
        alerts.pending().forEach { alert ->
            try {
                Notifier.show(ctx, alert)
                val now = clock.now()
                alerts.markDelivered(alert.id, now)
                settings.lastDeliveredAt = now
                sent++
            } catch (t: Throwable) {
                val msg = "${t.javaClass.simpleName}: ${t.message}"
                alerts.markAttemptFailed(alert.id, msg)
                LogBus.post("送达失败(#${alert.id})：$msg")
            }
        }
        return sent
    }

    /** 手动测试：绕开 outbox 直接发，用来二分定位链路。 */
    fun testNotification(): Boolean = try {
        Notifier.postTest(ctx)
        true
    } catch (t: Throwable) {
        LogBus.post("测试通知失败：${t.message}")
        false
    }
}
