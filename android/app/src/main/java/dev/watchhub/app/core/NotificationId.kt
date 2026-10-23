package dev.watchhub.app.core

/**
 * 通知 ID 策略。
 *
 * Android 通知的身份是 (tag, id) 二元组：只固定 tag 而 id 每条都不同，
 * 同一来源的多条通知仍然会堆叠。这里由 tag 派生稳定 id，让「同 tag 覆盖」真正生效。
 *
 * 不同 tag 即使 id 撞车也互不影响（身份还包含 tag），所以直接用 hashCode 即可。
 */
object NotificationId {
    fun of(tag: String): Int = tag.hashCode() and 0x7fffffff
}
