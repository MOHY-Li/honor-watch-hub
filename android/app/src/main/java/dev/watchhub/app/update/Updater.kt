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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.watchhub.app.BuildConfig
import dev.watchhub.app.core.LogBus
import dev.watchhub.app.net.Http
import java.io.File
import java.security.MessageDigest

/**
 * 自更新。
 *
 * Android 不允许应用静默装 APK，所以流程是：
 *   检查清单 → 系统 DownloadManager 下载 → 校验 SHA-256 + 大小 → FileProvider → 系统安装器
 *
 * 校验 SHA-256 不是可选项：更新包走公网，不校验等于允许别人替换 APK。
 */
object Updater {

    private const val APK_NAME = "watchhub-update.apk"

    /**
     * 清单候选地址，按顺序尝试。raw.githubusercontent.com 在国内经常不通，
     * jsDelivr 走国内 CDN，GitHub API 是第三条独立线路。
     */
    val MANIFEST_URLS: List<String> = listOf(
        "https://cdn.jsdelivr.net/gh/MOHY-Li/honor-watch-hub@master/dist/latest.json",
        "https://raw.githubusercontent.com/MOHY-Li/honor-watch-hub/master/dist/latest.json",
        "https://api.github.com/repos/MOHY-Li/honor-watch-hub/releases/latest",
    )

    /**
     * 检查新版本。网络操作，必须在后台线程调用。
     *
     * @param manifestUrl 指定单一地址（测试用）；传空则依次尝试 [MANIFEST_URLS]
     */
    fun check(
        manifestUrl: String = "",
        currentCode: Long = currentVersionCode(),
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): UpdateCheck {
        val candidates = if (manifestUrl.isBlank()) MANIFEST_URLS else listOf(manifestUrl)
        val errors = mutableListOf<String>()
        for (url in candidates) {
            try {
                val json = Http.execute(
                    "GET", url, mapOf("Accept" to "application/vnd.github+json"),
                ).text()
                val m = parseAny(json) ?: run {
                    errors.add("$url: 格式不对")
                    null
                } ?: continue
                return when {
                    m.versionCode <= currentCode -> UpdateCheck.UpToDate(currentCode)
                    m.minSdk > sdkInt -> UpdateCheck.Unsupported(m)
                    else -> UpdateCheck.Available(m)
                }
            } catch (t: Throwable) {
                errors.add("${t.javaClass.simpleName}: ${t.message}")
            }
        }
        return UpdateCheck.Failed("所有清单地址都失败：${errors.joinToString(" | ").take(300)}")
    }

    /** 兼容两种格式：自己的 latest.json 与 GitHub Releases API 响应。 */
    fun parseAny(json: String): UpdateManifest? =
        UpdateManifest.parse(json) ?: parseGitHubRelease(json)

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
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url")
                        size = a.optLong("size", 0)
                        sha = a.optString("digest").removePrefix("sha256:").lowercase()
                        break
                    }
                }
            }
            if (apkUrl.isBlank()) null else UpdateManifest(
                versionCode = versionCodeOf(tag),
                versionName = tag.removePrefix("v"),
                apkUrl = apkUrl,
                sha256 = sha,
                sizeBytes = size,
                changelog = o.optString("body", "").take(500),
                minSdk = 24,
                publishedAt = o.optString("published_at", ""),
            )
        }
    } catch (_: Throwable) {
        null
    }

    /** `v2.0.0` → 20000（主*10000 + 次*100 + 修）。 */
    fun versionCodeOf(tag: String): Long {
        val nums = tag.removePrefix("v").split(".", "-", "+")
            .mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        if (nums.isEmpty()) return 0L
        val major = nums.getOrElse(0) { 0 }
        val minor = nums.getOrElse(1) { 0 }
        val patch = nums.getOrElse(2) { 0 }
        return major * 10_000L + minor * 100L + patch
    }

    fun apkFile(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir, APK_NAME)

    /** 用系统 DownloadManager 下载（断点续传、系统级重试，不占我们的线程）。 */
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
                runCatching { c.unregisterReceiver(this) }
                val result = verifyFile(ctx, m)
                onDone(result.first, result.second)
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
        if (m.sha256.isBlank()) {
            return true to "校验通过（GitHub Releases 线路仅校验大小）"
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

    sealed class InstallResult {
        object Started : InstallResult()
        data class NeedPermission(val why: String) : InstallResult()
        data class Failed(val reason: String) : InstallResult()
    }

    fun install(ctx: Context): InstallResult {
        val f = apkFile(ctx)
        if (!f.exists()) return InstallResult.Failed("APK 不存在，可能被清理了")
        if (f.length() < 100_000) return InstallResult.Failed("APK 不完整：${f.length()} 字节")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            return try {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + ctx.packageName),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                InstallResult.NeedPermission("请允许 WatchHub 安装应用，然后返回重试")
            } catch (_: Throwable) {
                InstallResult.Failed("打不开授权页，请手动到 设置 → 应用 → 特殊权限 → 安装未知应用")
            }
        }

        return try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", f)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            LogBus.post("已拉起系统安装器")
            InstallResult.Started
        } catch (t: Throwable) {
            InstallResult.Failed("拉起安装器失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun currentVersionCode(): Long = BuildConfig.VERSION_CODE.toLong()
    fun currentVersionName(): String = BuildConfig.VERSION_NAME
}
