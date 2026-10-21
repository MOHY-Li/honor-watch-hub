package dev.watchhub.app

import android.content.Context
import android.content.SharedPreferences

/**
 * 极简配置存储。只有一个服务器地址 + token + 开机自启开关。
 * 故意用 SharedPreferences 而不是 DataStore：零依赖、同步读、Service 里直接可用。
 */
class Config(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("watchhub", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString(KEY_URL, DEFAULT_URL)!!.trim().trimEnd('/')
        set(v) = sp.edit().putString(KEY_URL, v.trim().trimEnd('/')).apply()

    var token: String
        get() = sp.getString(KEY_TOKEN, "")!!
        set(v) = sp.edit().putString(KEY_TOKEN, v.trim()).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean(KEY_BOOT, true)
        set(v) = sp.edit().putBoolean(KEY_BOOT, v).apply()

    var lastEventId: Long
        get() = sp.getLong(KEY_LAST_ID, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_ID, v).apply()

    /** 最近 Notified 的 id，用于跨进程重启后去重（服务端也会去重，这里是双保险）。 */
    private val seenKey = "seen_ids"

    fun isSeen(id: Long): Boolean = sp.getStringSet(seenKey, emptySet())!!.contains(id.toString())

    fun markSeen(id: Long) {
        val cur = sp.getStringSet(seenKey, emptySet())!!.toMutableSet()
        cur.add(id.toString())
        // 只保留最近 500 个
        val trimmed = if (cur.size > 500) cur.sortedByDescending { it.toLongOrNull() ?: 0L }
            .take(500).toSet() else cur
        sp.edit().putStringSet(seenKey, trimmed).apply()
    }

    fun eventsUrl(lastId: Long): String {
        val sb = StringBuilder(baseUrl).append("/api/v1/events")
        val q = mutableListOf<String>()
        if (token.isNotBlank()) q.add("token=" + java.net.URLEncoder.encode(token, "UTF-8"))
        if (lastId > 0) q.add("last_id=$lastId")
        if (q.isNotEmpty()) sb.append('?').append(q.joinToString("&"))
        return sb.toString()
    }

    fun healthUrl(): String = "$baseUrl/health"

    companion object {
        const val DEFAULT_URL = "http://192.168.1.10:8787"
        private const val KEY_URL = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_BOOT = "start_on_boot"
        private const val KEY_LAST_ID = "last_event_id"
    }
}
