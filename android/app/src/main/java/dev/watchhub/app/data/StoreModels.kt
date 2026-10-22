package dev.watchhub.app.data

import android.content.ContentValues
import org.json.JSONArray

/** 源的健康状态，UI 和体检页用。 */
data class SourceState(
    val name: String,
    val lastCheck: Long = 0,
    val lastOk: Long = 0,
    val lastError: String? = null,
    val fails: Int = 0,
    val itemsSeen: Int = 0,
    val seeded: Boolean = false,
) {
    val healthy: Boolean get() = lastOk > 0 && lastError == null
}

/** 今日任务统计。 */
data class TaskStats(
    val open: Int = 0,
    val done: Int = 0,
    val total: Int = 0,
)

internal fun tagsToJson(tags: List<String>): String {
    val arr = JSONArray()
    tags.forEach { arr.put(it) }
    return arr.toString()
}

internal fun tagsFromJson(s: String?): List<String> {
    if (s.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(s)
        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * 把「列表」写成 SQL。SQLite 没有数组绑定，只能拼占位符。
 * 空列表由调用方提前返回，不走到这里。
 */
internal fun placeholders(n: Int): String = List(n) { "?" }.joinToString(",")

internal fun ContentValues.putNullable(key: String, value: String?) {
    if (value == null) putNull(key) else put(key, value)
}
