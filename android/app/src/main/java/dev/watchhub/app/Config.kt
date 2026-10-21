package dev.watchhub.app

import android.content.Context

/** 全局开关（不再有服务器地址 / token —— 服务端已经在本机）。 */
class Config(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("watchhub", Context.MODE_PRIVATE)

    var startOnBoot: Boolean
        get() = sp.getBoolean("start_on_boot", true)
        set(v) = sp.edit().putBoolean("start_on_boot", v).apply()

    /** 用户是否希望中枢在跑（决定开机自启与 UI 状态）。 */
    var wantRun: Boolean
        get() = sp.getBoolean("want_run", false)
        set(v) = sp.edit().putBoolean("want_run", v).apply()

    var lastTick: Long
        get() = sp.getLong("last_tick", 0L)
        set(v) = sp.edit().putLong("last_tick", v).apply()
}
