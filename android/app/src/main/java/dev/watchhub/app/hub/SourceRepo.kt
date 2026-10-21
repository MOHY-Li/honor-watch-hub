package dev.watchhub.app.hub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 源的配置仓库。存 JSON 字符串在 SharedPreferences 里，首次启动写入内置默认配置。
 *
 * 为什么用 JSON 而不是 YAML：Android 上解析 JSON 零依赖（org.json 内置），
 * 而且用户在 App 里编辑配置时可以直接填 JSON。
 */
class SourceRepo(ctx: Context) {

    private val sp = ctx.applicationContext.getSharedPreferences("watchhub_sources", Context.MODE_PRIVATE)

    fun load(): List<Source> {
        val text = sp.getString(KEY, null) ?: DEFAULT_JSON.also { sp.edit().putString(KEY, it).apply() }
        return runCatching { Source.fromArray(JSONArray(text)) }.getOrDefault(emptyList())
    }

    fun save(sources: List<Source>) {
        val arr = JSONArray()
        sources.forEach { arr.put(it.raw) }
        sp.edit().putString(KEY, arr.toString(2)).apply()
    }

    fun saveRaw(json: String): Result<List<Source>> = runCatching {
        val arr = JSONArray(json)
        val list = Source.fromArray(arr)
        require(list.size == arr.length()) { "有 " + (arr.length() - list.size) + " 个源配置不合法（检查 name / kind）" }
        sp.edit().putString(KEY, arr.toString(2)).apply()
        list
    }

    fun rawJson(): String = sp.getString(KEY, DEFAULT_JSON) ?: DEFAULT_JSON

    fun reset() = sp.edit().putString(KEY, DEFAULT_JSON).apply()

    companion object {
        private const val KEY = "sources_json"

        /** 内置示例：番剧（Bangumi，实测可用）+ 工作日喝水 + 一个网页指纹模板。 */
        val DEFAULT_JSON: String = """
        [
          {
            "name": "bangumi",
            "kind": "json",
            "url": "https://api.bgm.tv/v0/episodes?subject_id=425998&type=0&limit=20",
            "list_path": "data",
            "limit": 20,
            "interval": 900,
            "priority": "high",
            "tags": ["anime", "番剧"],
            "fields": {"title": "name_cn", "key": "id", "body": "airdate", "url": "id"}
          },
          {
            "name": "drink_water",
            "kind": "cron",
            "times": ["09:30", "11:30", "14:30", "16:30"],
            "days": [0, 1, 2, 3, 4],
            "title": "喝水",
            "body": "起来走两步，喝口水",
            "priority": "high",
            "tags": ["health"]
          },
          {
            "name": "page_watch",
            "kind": "hash",
            "url": "https://example.com/product/123",
            "interval": 1800,
            "title": "商品页有变化",
            "body": "价格或库存更新了",
            "priority": "high",
            "tags": ["shopping"],
            "enabled": false
          }
        ]
        """.trimIndent()
    }
}

