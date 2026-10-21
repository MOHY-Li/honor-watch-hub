package dev.watchhub.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机 / 应用更新后自动恢复长连接（受「开机自动连接」开关控制）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val cfg = Config(ctx)
        if (!cfg.startOnBoot) return
        runCatching { SseService.start(ctx) }
    }
}
