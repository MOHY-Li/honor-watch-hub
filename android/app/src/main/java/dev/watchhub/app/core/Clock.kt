package dev.watchhub.app.core

/**
 * 时间抽象。所有可测逻辑都必须用 Clock，禁止在 core/engine/source 里直接调 System.currentTimeMillis()。
 *
 * 单测里用 [FixedClock] 精确控制「现在」。
 */
interface Clock {
    fun now(): Long
}

object WallClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}

class FixedClock(private var current: Long) : Clock {
    override fun now(): Long = current

    fun set(ms: Long) {
        current = ms
    }

    fun advance(ms: Long) {
        current += ms
    }
}
