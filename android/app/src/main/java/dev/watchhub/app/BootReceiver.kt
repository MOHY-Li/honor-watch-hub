package dev.watchhub.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机 / 应用更新后自动恢复中枢。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val cfg = Config(ctx)
        if (!cfg.startOnBoot || !cfg.wantRun) return
        runCatching { HubService.start(ctx) }
    }
}
