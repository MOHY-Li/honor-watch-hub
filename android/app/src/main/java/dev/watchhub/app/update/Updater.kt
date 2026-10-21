package dev.watchhub.app.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.watchhub.app.BuildConfig
import dev.watchhub.app.LogBus
import dev.watchhub.app.hub.Http
import java.io.File
import java.security.MessageDigest

/**
 * 自更新。
 *
 * 为什么不能「静默更新」：Android 从 7.0 起禁止普通应用自己装 APK，
 * 必须交给系统安装器（用户点一下「安装」）。所以流程是：
 *   检查清单 -> 下载到私有目录 -> 校验 SHA-256 -> FileProvider 暴露 -> 拉起系统安装器
 *
 * 校验 SHA-256 很重要：更新包经过网络，必须确认没被改过，
 * 否则等于给攻击者开了一个远程代码执行的口子。
 */
object Updater {

    private const val TAG = "WatchHubUpdater"
    private const val APK_NAME = "watchhub-update.apk"

    /** 默认清单地址，指向仓库 raw 文件。CI 每次发布时覆盖它。 */
    val DEFAULT_MANIFEST_URL: String =
        "https://raw.githubusercontent.com/MOHY-Li/honor-watch-hub/master/dist/latest.json"

    /** 检查新版本。网络操作，必须在后台线程调用。 */
    fun check(
        manifestUrl: String,
        currentCode: Long,
        // 可注入，方便单测；生产调用用默认值
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): UpdateCheck {
        return try {
            val json = Http.getString(manifestUrl)
            val m = UpdateManifest.parse(json)
                ?: return UpdateCheck.Failed("清单格式不对（不是合法的更新 JSON）")
            when {
                m.versionCode <= currentCode -> UpdateCheck.UpToDate(currentCode)
                m.minSdk > sdkInt -> UpdateCheck.Unsupported(m)
                else -> UpdateCheck.Available(m)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "check failed", t)
            UpdateCheck.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** 已下载的 APK 位置（私有外部目录，不需要存储权限）。 */
    fun apkFile(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir, APK_NAME)

    /**
     * 用系统 DownloadManager 下载。
     * 用它而不是自己写 HTTP：支持断点续传、系统级重试，且不占我们的线程。
     */
    fun download(ctx: Context, m: UpdateManifest, onDone: (Boolean, String) -> Unit): Long {
        val target = apkFile(ctx)
        if (target.exists()) target.delete()

        val request = DownloadManager.Request(Uri.parse(m.apkUrl)).apply {
            setTitle("WatchHub " + m.versionName)
            setDescription("正在下载更新")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, APK_NAME)
            setAllowedOverRoaming(false)
        }
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                c.unregisterReceiver(this)
                val res = verifyFile(ctx, m)
                onDone(res.first, res.second)
            }
        }
        ContextCompat.registerReceiver(
            ctx, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
        return id
    }

    /** 校验大小与 SHA-256。返回 (是否通过, 说明)。 */
    fun verifyFile(ctx: Context, m: UpdateManifest): Pair<Boolean, String> {
        val f = apkFile(ctx)
        if (!f.exists()) return false to "下载的文件不存在"
        if (m.sizeBytes > 0 && f.length() != m.sizeBytes) {
            return false to "大小不符（期望 ${m.sizeBytes}，实际 ${f.length()}）"
        }
        val actual = sha256(f)
        return if (actual.equals(m.sha256, ignoreCase = true)) {
            true to "校验通过"
        } else {
            f.delete()
            false to "SHA-256 不匹配，已删除（可能下载损坏或被篡改）"
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 拉起系统安装器。Android 8+ 需要先授予「安装未知应用」权限。 */
    fun install(ctx: Context): Result<Unit> = runCatching {
        val f = apkFile(ctx)
        require(f.exists()) { "APK 不存在" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !ctx.packageManager.canRequestPackageInstalls()
        ) {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return@runCatching
        }

        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", f)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        LogBus.post("已拉起系统安装器")
    }

    fun currentVersionCode(): Long = BuildConfig.VERSION_CODE.toLong()
    fun currentVersionName(): String = BuildConfig.VERSION_NAME
}
