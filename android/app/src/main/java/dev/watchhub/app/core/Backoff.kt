package dev.watchhub.app.core

/**
 * 数据源失败退避（纯函数，无 Android 依赖）。
 *
 * 源连续失败达到 [FAIL_THRESHOLD] 次后，轮询间隔每次翻倍，封顶 [MAX_INTERVAL_SEC]（6 小时）；
 * 封顶取 max(base, MAX)——基础间隔本来就比 6h 长的源（如每日一查）不会被退避变快。
 * 一次抓取成功即清零 fails（Poller 既有行为），恢复正常间隔。
 */
object Backoff {

    /** 连续失败达到此次数后开始退避。 */
    const val FAIL_THRESHOLD = 3

    /** 退避后的间隔上限（秒）。 */
    const val MAX_INTERVAL_SEC = 6 * 3600

    fun effectiveIntervalSec(baseSec: Int, fails: Int): Int {
        if (baseSec <= 0) return baseSec
        if (fails < FAIL_THRESHOLD) return baseSec
        val cap = maxOf(baseSec.toLong(), MAX_INTERVAL_SEC.toLong())
        // 2^30 * 任何 base 已远超 cap，指数再大无意义，clamp 防移位溢出
        val shift = (fails - 2).coerceAtMost(30)
        val doubled = baseSec.toLong() shl shift
        return minOf(doubled, cap).toInt()
    }

    /** 展示用：秒数转「N 秒 / N 分钟 / N 小时」，分钟与小时向上取整。 */
    fun intervalText(sec: Int): String = when {
        sec < 60 -> "$sec 秒"
        sec < 3600 -> "${(sec + 59) / 60} 分钟"
        else -> "${(sec + 3599) / 3600} 小时"
    }
}
