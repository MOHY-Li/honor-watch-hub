package dev.watchhub.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.watchhub.app.source.SourceDef
import org.json.JSONArray
import org.json.JSONObject

/** 数据源配置 + 每个源的运行状态。源配置以原始 JSON 文本存，方便用户手改。 */
class SourceStore(private val db: Db) {

    fun all(): List<SourceDef> {
        val out = ArrayList<SourceDef>()
        db.readableDatabase.rawQuery("SELECT spec, enabled FROM sources ORDER BY name", null).use { c ->
            while (c.moveToNext()) {
                val def = runCatching { SourceDef.parse(JSONObject(c.getString(0))) }.getOrNull() ?: continue
                out.add(def.copy(enabled = c.getInt(1) == 1))
            }
        }
        return out
    }

    fun get(name: String): SourceDef? {
        db.readableDatabase.rawQuery("SELECT spec, enabled FROM sources WHERE name=?", arrayOf(name)).use { c ->
            if (!c.moveToFirst()) return null
            val def = runCatching { SourceDef.parse(JSONObject(c.getString(0))) }.getOrNull() ?: return null
            return def.copy(enabled = c.getInt(1) == 1)
        }
    }

    fun upsert(def: SourceDef) {
        db.writableDatabase.insertWithOnConflict(
            "sources", null,
            ContentValues().apply {
                put("name", def.name)
                put("spec", def.toJson())
                put("enabled", if (def.enabled) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** 保存用户编辑过的 JSON 数组。全部解析成功才落库，避免存进半截配置。 */
    fun replaceAll(json: String) {
        val defs = SourceDef.parseAll(json)
        val db = db.writableDatabase
        db.beginTransaction()
        try {
            db.delete("sources", null, null)
            defs.forEach { upsert(it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setEnabled(name: String, enabled: Boolean) {
        db.writableDatabase.execSQL(
            "UPDATE sources SET enabled=? WHERE name=?",
            arrayOf(if (enabled) 1 else 0, name),
        )
    }

    fun delete(name: String) {
        db.writableDatabase.delete("sources", "name=?", arrayOf(name))
        db.writableDatabase.delete("source_state", "name=?", arrayOf(name))
    }

    fun isEmpty(): Boolean =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM sources", null)
            .use { it.moveToFirst() && it.getInt(0) == 0 }

    fun rawJson(): String {
        val arr = JSONArray()
        all().forEach { arr.put(it.raw) }
        return arr.toString(2)
    }

    // ---------- 状态 ----------

    fun stateOf(name: String): SourceState {
        db.readableDatabase.rawQuery(
            "SELECT last_check,last_ok,last_error,fails,items_seen,seeded FROM source_state WHERE name=?",
            arrayOf(name),
        ).use { c ->
            if (!c.moveToFirst()) return SourceState(name)
            return SourceState(
                name = name,
                lastCheck = c.getLong(0),
                lastOk = c.getLong(1),
                lastError = if (c.isNull(2)) null else c.getString(2),
                fails = c.getInt(3),
                itemsSeen = c.getInt(4),
                seeded = c.getInt(5) == 1,
            )
        }
    }

    fun saveState(s: SourceState) {
        db.writableDatabase.insertWithOnConflict(
            "source_state", null,
            ContentValues().apply {
                put("name", s.name)
                put("last_check", s.lastCheck)
                put("last_ok", s.lastOk)
                putNullable("last_error", s.lastError)
                put("fails", s.fails)
                put("items_seen", s.itemsSeen)
                put("seeded", if (s.seeded) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun failingSources(threshold: Int = 3): List<SourceState> =
        all().map { stateOf(it.name) }.filter { it.fails >= threshold }
}
