package dev.watchhub.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** 键值设置。业务语义放在属性里，避免 UI 里到处出现裸字符串 key。 */
class SettingsStore(private val db: Db) {

    fun getString(key: String, def: String? = null): String? =
        db.readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key))
            .use { if (it.moveToFirst()) it.getString(0) else def }

    fun putString(key: String, value: String) {
        db.writableDatabase.insertWithOnConflict(
            "settings", null,
            ContentValues().apply {
                put("key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun getLong(key: String, def: Long = 0L): Long = getString(key)?.toLongOrNull() ?: def

    fun putLong(key: String, value: Long) = putString(key, value.toString())

    fun getBool(key: String, def: Boolean = false): Boolean = when (getString(key)) {
        "1", "true" -> true
        "0", "false" -> false
        else -> def
    }

    fun putBool(key: String, value: Boolean) = putString(key, if (value) "1" else "0")

    fun remove(key: String) {
        db.writableDatabase.delete("settings", "key=?", arrayOf(key))
    }

    // ---------- 语义化设置 ----------

    /** 用户是否希望中枢在跑（服务被杀后看门狗/开机是否拉起）。 */
    var wantRun: Boolean
        get() = getBool(KEY_WANT_RUN, false)
        set(v) = putBool(KEY_WANT_RUN, v)

    var startOnBoot: Boolean
        get() = getBool(KEY_START_ON_BOOT, true)
        set(v) = putBool(KEY_START_ON_BOOT, v)

    /** 前端服务心跳时间戳，看门狗用它判断服务是否还活着。 */
    var heartbeatAt: Long
        get() = getLong(KEY_HEARTBEAT, 0)
        set(v) = putLong(KEY_HEARTBEAT, v)

    var lastDeliveredAt: Long
        get() = getLong(KEY_LAST_DELIVERED, 0)
        set(v) = putLong(KEY_LAST_DELIVERED, v)

    var calendarEnabled: Boolean
        get() = getBool(KEY_CALENDAR_ENABLED, false)
        set(v) = putBool(KEY_CALENDAR_ENABLED, v)

    var migratedV1: Boolean
        get() = getBool(KEY_MIGRATED_V1, false)
        set(v) = putBool(KEY_MIGRATED_V1, v)

    companion object {
        private const val KEY_WANT_RUN = "want_run"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_HEARTBEAT = "heartbeat_at"
        private const val KEY_LAST_DELIVERED = "last_delivered_at"
        private const val KEY_CALENDAR_ENABLED = "calendar_enabled"
        private const val KEY_MIGRATED_V1 = "migrated_v1"
    }
}
