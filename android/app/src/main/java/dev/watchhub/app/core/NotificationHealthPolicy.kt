package dev.watchhub.app.core

/**
 * 提醒渠道重要性的判定策略（纯函数，可单测）。
 *
 * 为什么不用「必须 IMPORTANCE_HIGH」作为体检标准：
 * 部分荣耀/华为 ROM 的通知设置里只提供「默认」，没有「高/重要」；
 * 而手表镜像依赖的是「通知被发出 + 运动健康勾选 WatchHub」，不是档位本身。
 * 把 DEFAULT 判成异常只会让用户去改一个不存在的开关。
 *
 * 真正的异常只有两种：渠道被关闭（NONE）、或被调到「低/静默」。
 *
 * importance 取值与 Android NotificationManager 常量一致：
 * 0=NONE, 1=MIN, 2=LOW, 3=DEFAULT, 4=HIGH, 5=MAX。
 */
object NotificationHealthPolicy {

    enum class ChannelState { OFF, LOW, DEFAULT, HIGH }

    fun classify(importance: Int): ChannelState = when {
        importance <= 0 -> ChannelState.OFF
        importance <= 2 -> ChannelState.LOW
        importance == 3 -> ChannelState.DEFAULT
        else -> ChannelState.HIGH
    }

    /** DEFAULT 及以上都可用；只有 OFF / LOW 需要处理。 */
    fun isAcceptable(state: ChannelState): Boolean =
        state == ChannelState.DEFAULT || state == ChannelState.HIGH
}
