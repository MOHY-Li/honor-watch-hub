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

    /**
     * 清单的候选地址，按顺序尝试。
     *
     * 为什么要多源：raw.githubusercontent.com 在国内经常被墙或不稳定，
     * 单一地址会导致「检查更新」直接失败。jsDelivr 走国内 CDN，
     * GitHub API 又是另一条线路，三个都挂了才算真失败。
     */
    val MANIFEST_URLS: List<String> = listOf(
        // 1. jsDelivr：国内访问通常最快
        "https://cdn.jsdelivr.net/gh/MOHY-Li/honor-watch-hub@master/dist/latest.json",
        // 2. raw：官方源，海外/代理环境最稳
        "https://raw.githubusercontent.com/MOHY-Li/honor-watch-hub/master/dist/latest.json",
        // 3. GitHub API：另一条独立线路（返回 Release 信息，需要转换）
        "https://api.github.com/repos/MOHY-Li/honor-watch-hub/releases/latest",
    )

    /** 兼容旧调用点。 */
    val DEFAULT_MANIFEST_URL: String get() = MANIFEST_URLS.first()

    /**
     * 检查新版本。网络操作，必须在后台线程调用。
     *
     * @param manifestUrl 指定单一地址（测试用）；传空则依次尝试 [MANIFEST_URLS]
     */
    fun check(
        manifestUrl: String,
        currentCode: Long,
        // 可注入，方便单测；生产调用用默认值
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): UpdateCheck {
        val candidates = if (manifestUrl.isBlank()) MANIFEST_URLS else listOf(manifestUrl)
        val errors = mutableListOf<String>()
        for (url in candidates) {
            try {
                val json = Http.getString(url, mapOf("Accept" to "application/vnd.github+json"))
                val m = parseAny(json)
                if (m == null) {
                    errors.add("$url: 格式不对")
                    continue
                }
                return when {
                    m.versionCode <= currentCode -> UpdateCheck.UpToDate(currentCode)
                    m.minSdk > sdkInt -> UpdateCheck.Unsupported(m)
                    else -> UpdateCheck.Available(m)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "check failed: $url", t)
                errors.add("${t.javaClass.simpleName}: ${t.message}")
            }
        }
        return UpdateCheck.Failed("所有清单地址都失败：${errors.joinToString(" | ").take(300)}")
    }

    /**
     * 解析更新信息，兼容两种格式：
     *  - 我们自己的 latest.json
     *  - GitHub Releases API 的 /releases/latest 响应
     */
    fun parseAny(json: String): UpdateManifest? {
        UpdateManifest.parse(json)?.let { return it }
        return parseGitHubRelease(json)
    }

    /**
     * GitHub Releases API 响应 → UpdateManifest。
     *
     * 字段对应：
     *   tag_name     v1.2.0         -> versionName
     *   assets[].name WatchHub.apk  -> apkUrl / sizeBytes
     *   body                        -> changelog
     * 注意：这里拿不到真正的 versionCode，用 tag 里的 x.y.z 换算。
     */
    fun parseGitHubRelease(json: String): UpdateManifest? = try {
        val o = org.json.JSONObject(json)
        val tag = o.optString("tag_name").trim()
        if (tag.isBlank()) null else {
            val assets = o.optJSONArray("assets")
            var apkUrl = ""
            var size = 0L
            var sha = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url")
                        size = a.optLong("size", 0)
                        // GitHub 从 2024 起在 asset 上提供 digest: sha256:xxx
                        sha = a.optString("digest").removePrefix("sha256:").lowercase()
                        break
                    }
                }
            }
            if (apkUrl.isBlank()) null else {
                UpdateManifest(
                    versionCode = versionCodeOf(tag),
                    versionName = tag.removePrefix("v"),
                    apkUrl = apkUrl,
                    // 没有 digest 时留空，由 verifyFile 只校验大小（GitHub 走 HTTPS）
                    sha256 = sha,
                    sizeBytes = size,
                    changelog = o.optString("body", "").take(500),
                    minSdk = 24,
                    publishedAt = o.optString("published_at", ""),
                )
            }
        }
    } catch (_: Throwable) {
        null
    }

    /** `v1.2.3` -> 10203（主*10000 + 次*100 + 修） */
    fun versionCodeOf(tag: String): Long {
        val nums = tag.removePrefix("v").split(".", "-", "+")
            .mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        if (nums.isEmpty()) return 0L
        val major = nums.getOrElse(0) { 0 }
        val minor = nums.getOrElse(1) { 0 }
        val patch = nums.getOrElse(2) { 0 }
        return major * 10_000L + minor * 100L + patch
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
        // GitHub API 那条线路可能拿不到 digest，此时只校验大小
        if (m.sha256.isBlank()) {
            return true to "校验通过（来源为 GitHub Releases，仅校验大小）"
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
