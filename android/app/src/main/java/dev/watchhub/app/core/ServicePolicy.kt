package dev.watchhub.app.core

/**
 * 前台服务循环的纯策略。抽成纯函数是为了能单测——服务本身无法在 JVM 里跑。
 *
 * 设计取舍（见 docs/specs/2026-10-23-watchhub-2.2.0.md）：
 * - 任务准点性由 AlarmManager 保证，轮询循环只负责「尽快补处理」，不需要一直 20s。
 * - 只有临近到期（2 分钟内）才用快节奏，其余 60s，降低常驻耗电。
 */
object ServicePolicy {

    /** 有临近到期任务时的循环间隔。 */
    const val FAST_TICK_MS = 20_000L

    /** 没有临近任务时的循环间隔。 */
    const val SLOW_TICK_MS = 60_000L

    /** 「临近到期」窗口：2 分钟。 */
    const val FAST_WINDOW_MS = 2 * 60_000L

    /** 任务补齐（materialize）的最小间隔：1 小时。 */
    const val MATERIALIZE_INTERVAL_MS = 60 * 60_000L

    /**
     * 下一次到期在 [FAST_WINDOW_MS] 内（含已过期）或用快节奏；
     * 升级链激活时也用快节奏——1 分钟级提醒需要更密集的 tick 才能及时派发。
     */
    fun tickIntervalMs(now: Long, nextDueAt: Long?, escalationActive: Boolean = false): Long =
        if (escalationActive || (nextDueAt != null && nextDueAt - now <= FAST_WINDOW_MS)) {
            FAST_TICK_MS
        } else {
            SLOW_TICK_MS
        }

    /**
     * 是否该补任务。从未补过（last <= 0）或距上次超过 1 小时则补；
     * now < last 说明系统时间被回拨，也补一次，避免长时间不补。
     */
    fun shouldMaterialize(now: Long, lastMaterializeAt: Long): Boolean =
        lastMaterializeAt <= 0L ||
            now < lastMaterializeAt ||
            now - lastMaterializeAt >= MATERIALIZE_INTERVAL_MS

    /** 目标时间没变就不动 AlarmManager；从 null 到有值、有值到 null 都算变化。 */
    fun shouldRearm(previousTarget: Long?, nextTarget: Long?): Boolean =
        previousTarget != nextTarget

    /** 常驻通知文案没变就不刷新；首次（previous 为 null）要刷。 */
    fun shouldUpdateNotification(previousText: String?, nextText: String): Boolean =
        previousText != nextText
}
