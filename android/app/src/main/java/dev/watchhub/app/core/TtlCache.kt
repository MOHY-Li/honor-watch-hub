package dev.watchhub.app.core

/**
 * 带 TTL 的进程内缓存。时间由调用方传入，便于单测。
 *
 * 用途：首页每 15 秒刷新一次，但体检里的 PackageManager / AlarmManager 查询
 * 没必要同样高频；缓存 30 秒即可。
 *
 * 时间回拨（now < storedAt）视为过期并重算，避免时间同步后长时间不刷新。
 */
class TtlCache<T : Any>(private val ttlMs: Long) {

    private var value: T? = null
    private var storedAt: Long = 0L

    @Synchronized
    fun get(now: Long, compute: () -> T): T {
        val cached = value
        if (cached != null && now >= storedAt && now - storedAt < ttlMs) {
            return cached
        }
        val fresh = compute()
        value = fresh
        storedAt = now
        return fresh
    }

    @Synchronized
    fun invalidate() {
        value = null
    }
}
