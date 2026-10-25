package dev.watchhub.app.data

import android.content.ContentValues
import dev.watchhub.app.core.model.HealthReport
import dev.watchhub.app.core.model.HealthRule
import dev.watchhub.app.core.model.MetricSample
import dev.watchhub.app.core.model.MetricType
import dev.watchhub.app.core.model.Priority
import dev.watchhub.app.core.model.RuleOp

/** 指标样本的写入/查询。只追加，不更新；过期清理走 [prune]。 */
class MetricStore(private val db: Db) {

    fun insertAll(samples: List<MetricSample>) {
        if (samples.isEmpty()) return
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            samples.forEach { s ->
                w.insert(
                    "metrics", null,
                    ContentValues().apply {
                        put("type", s.type.name)
                        put("value", s.value)
                        put("at", s.at)
                    },
                )
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    /** 每种指标的最新一个样本。 */
    fun latestEach(): Map<MetricType, MetricSample> {
        val out = HashMap<MetricType, MetricSample>()
        db.readableDatabase.rawQuery(
            "SELECT type, value, at FROM metrics ORDER BY at ASC", null,
        ).use { c ->
            while (c.moveToNext()) {
                val type = MetricType.parse(c.getString(0)) ?: continue
                out[type] = MetricSample(type, c.getDouble(1), c.getLong(2))
            }
        }
        return out
    }

    /** 某指标自 sinceMs 起的样本（升序），给规则引擎做窗口判定。 */
    fun window(type: MetricType, sinceMs: Long): List<MetricSample> {
        val out = ArrayList<MetricSample>()
        db.readableDatabase.rawQuery(
            "SELECT type, value, at FROM metrics WHERE type=? AND at>=? ORDER BY at ASC",
            arrayOf(type.name, sinceMs.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val t = MetricType.parse(c.getString(0)) ?: continue
                out.add(MetricSample(t, c.getDouble(1), c.getLong(2)))
            }
        }
        return out
    }

    /** 全部指标自 sinceMs 起的样本（AI 分析聚合用）。 */
    fun since(sinceMs: Long): List<MetricSample> {
        val out = ArrayList<MetricSample>()
        db.readableDatabase.rawQuery(
            "SELECT type, value, at FROM metrics WHERE at>=? ORDER BY at ASC",
            arrayOf(sinceMs.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val t = MetricType.parse(c.getString(0)) ?: continue
                out.add(MetricSample(t, c.getDouble(1), c.getLong(2)))
            }
        }
        return out
    }

    fun prune(olderThanMs: Long) {
        db.writableDatabase.delete("metrics", "at<?", arrayOf(olderThanMs.toString()))
    }
}

/** 健康阈值规则的 CRUD 与冷却状态。 */
class HealthRuleStore(private val db: Db) {

    fun all(): List<HealthRule> = query("SELECT * FROM health_rules ORDER BY id")

    fun byId(id: Long): HealthRule? =
        query("SELECT * FROM health_rules WHERE id=?", arrayOf(id.toString())).firstOrNull()

    fun upsert(r: HealthRule): Long {
        val cv = ContentValues().apply {
            put("name", r.name.trim())
            put("metric", r.metric.name)
            put("op", r.op.name)
            put("threshold", r.threshold)
            put("sustained_min", r.sustainedMin)
            put("cooldown_min", r.cooldownMin)
            put("priority", r.priority.name)
            put("enabled", if (r.enabled) 1 else 0)
            put("last_alert_at", r.lastAlertAt)
        }
        return if (r.id == 0L) {
            db.writableDatabase.insertWithOnConflict("health_rules", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT)
        } else {
            db.writableDatabase.updateWithOnConflict(
                "health_rules", cv, "id=?", arrayOf(r.id.toString()), android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
            )
            r.id
        }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        db.writableDatabase.execSQL(
            "UPDATE health_rules SET enabled=? WHERE id=?",
            arrayOf(if (enabled) 1 else 0, id.toString()),
        )
    }

    fun updateLastAlertAt(id: Long, at: Long) {
        db.writableDatabase.execSQL(
            "UPDATE health_rules SET last_alert_at=? WHERE id=?",
            arrayOf(at.toString(), id.toString()),
        )
    }

    fun delete(id: Long) {
        db.writableDatabase.delete("health_rules", "id=?", arrayOf(id.toString()))
    }

    private fun query(sql: String, args: Array<String>? = null): List<HealthRule> {
        val out = ArrayList<HealthRule>()
        db.readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val metric = MetricType.parse(c.getString(c.getColumnIndexOrThrow("metric"))) ?: continue
                out.add(
                    HealthRule(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        metric = metric,
                        op = RuleOp.parse(c.getString(c.getColumnIndexOrThrow("op"))),
                        threshold = c.getDouble(c.getColumnIndexOrThrow("threshold")),
                        sustainedMin = c.getInt(c.getColumnIndexOrThrow("sustained_min")),
                        cooldownMin = c.getInt(c.getColumnIndexOrThrow("cooldown_min")),
                        priority = Priority.parse(c.getString(c.getColumnIndexOrThrow("priority"))),
                        enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) == 1,
                        lastAlertAt = c.getLong(c.getColumnIndexOrThrow("last_alert_at")),
                    ),
                )
            }
        }
        return out
    }
}

/** AI 分析报告的存档。只增不改，保留最近 [KEEP] 份。 */
class HealthReportStore(private val db: Db) {

    fun insert(r: HealthReport): Long =
        db.writableDatabase.insert(
            "health_reports", null,
            ContentValues().apply {
                put("created_at", r.createdAt)
                put("window_days", r.windowDays)
                put("model", r.model)
                put("content", r.content)
            },
        )

    fun recent(limit: Int = KEEP): List<HealthReport> {
        val out = ArrayList<HealthReport>()
        db.readableDatabase.rawQuery(
            "SELECT id, created_at, window_days, model, content FROM health_reports ORDER BY created_at DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    HealthReport(
                        id = c.getLong(0),
                        createdAt = c.getLong(1),
                        windowDays = c.getInt(2),
                        model = c.getString(3),
                        content = c.getString(4),
                    ),
                )
            }
        }
        return out
    }

    /** 只保留最近 keep 份。 */
    fun prune(keep: Int = KEEP) {
        db.writableDatabase.execSQL(
            "DELETE FROM health_reports WHERE id NOT IN (SELECT id FROM health_reports ORDER BY created_at DESC LIMIT $keep)",
        )
    }

    companion object {
        const val KEEP = 20
    }
}
