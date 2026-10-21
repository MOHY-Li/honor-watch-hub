package dev.watchhub.app.hub

import org.json.JSONObject
import org.json.JSONArray
import java.util.Calendar

/**
 * 定时提醒。本地计算，完全不联网。
 *
 * 配置：
 *   times: ["09:30", "14:00"]   24 小时制，可多个
 *   days:  [0,1,2,3,4]          0=周一 … 6=周日；缺省=每天
 *   title / body / priority
 *
 * 去重 key = 名字 + yyyyMMddHHmm，所以同一分钟绝不会重复，重启也不会补发历史。
 */
class CronSource(override val name: String, override val raw: JSONObject) : Source() {

    // 定时提醒没有「历史」概念：命中了现在就该响
    override val notifyOnFirstRun: Boolean get() = true

    override fun fetch(): List<Item> {
        val now = Calendar.getInstance()
        val hm = "%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))

        val times = when (val t = raw.opt("times")) {
            is JSONArray -> (0 until t.length()).map { t.optString(it) }
            is String -> listOf(t)
            else -> emptyList()
        }
        if (times.none { it.trim().take(5) == hm }) return emptyList()

        val daysArr = raw.optJSONArray("days")
        if (daysArr != null && daysArr.length() > 0) {
            // Calendar.DAY_OF_WEEK: 周日=1 … 周六=7；这里转成 0=周一
            val dow = now.get(Calendar.DAY_OF_WEEK)
            val mondayBased = if (dow == Calendar.SUNDAY) 6 else dow - 2
            val allowed = (0 until daysArr.length()).map { daysArr.optInt(it) }
            if (mondayBased !in allowed) return emptyList()
        }

        val stamp = "%04d%02d%02d%02d%02d".format(
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)
        )
        return listOf(
            Item(
                key = "$name:$stamp",
                title = raw.optString("title", name),
                body = raw.optString("body", ""),
                detail = raw.optString("detail", ""),
                priority = raw.optString("priority", "high"),
                tags = tags.ifEmpty { listOf("reminder") },
                source = name,
            )
        )
    }
}
