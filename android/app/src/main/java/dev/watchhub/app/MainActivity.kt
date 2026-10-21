package dev.watchhub.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.watchhub.app.databinding.ActivityMainBinding
import dev.watchhub.app.databinding.ItemSourceBinding
import dev.watchhub.app.hub.Engine
import dev.watchhub.app.hub.SourceRepo
import dev.watchhub.app.hub.Store
import dev.watchhub.app.update.UpdateCheck
import dev.watchhub.app.update.UpdateManifest
import dev.watchhub.app.update.Updater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var cfg: Config
    private lateinit var repo: SourceRepo
    private lateinit var store: Store
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private var pendingUpdate: UpdateManifest? = null

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast("没有通知权限，手表收不到任何东西")
        }

    private val editSources =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    private val logListener: (List<String>) -> Unit = { lines ->
        b.tvLog.text = lines.takeLast(30).joinToString("\n").ifBlank { "（暂无）" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        cfg = Config(this)
        repo = SourceRepo(this)
        store = Store(this)

        b.swBoot.isChecked = cfg.startOnBoot
        b.swBoot.setOnCheckedChangeListener { _, v -> cfg.startOnBoot = v }

        b.btnToggle.setOnClickListener {
            if (cfg.wantRun) {
                cfg.wantRun = false
                HubService.stop(this)
                toast("中枢已停止")
            } else {
                ensurePermissions()
                cfg.wantRun = true
                HubService.start(this)
                toast("中枢已启动")
            }
            refresh()
        }

        b.btnTest.setOnClickListener {
            Notifier.show(
                this,
                Notif(
                    id = System.currentTimeMillis() % 100000,
                    ts = "",
                    title = "测试通知",
                    body = "手表上看到这条，说明镜像通了 🎉",
                    detail = "",
                    priority = "high",
                    tags = listOf("test"),
                    url = "",
                    source = "local",
                )
            )
        }

        b.tvVersion.text = "版本 ${Updater.currentVersionName()} (${Updater.currentVersionCode()})"

        b.btnUpdate.setOnClickListener {
            if (pendingUpdate != null) {
                downloadAndInstall(pendingUpdate!!)
            } else {
                checkUpdate(manual = true)
            }
        }

        b.btnBattery.setOnClickListener { openBatterySettings() }
        b.btnEditSources.setOnClickListener {
            editSources.launch(Intent(this, SourcesActivity::class.java))
        }
        b.btnResetSources.setOnClickListener {
            repo.reset()
            refresh()
            toast("已恢复默认配置")
        }
    }

    override fun onStart() {
        super.onStart()
        LogBus.addListener(logListener)
        refresh()
        // 每次进前台静默检查一次（失败不打扰用户）
        if (pendingUpdate == null) checkUpdate(manual = false)
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canPost(this)) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStop() {
        super.onStop()
        LogBus.removeListener(logListener)
    }

    private fun refresh() {
        val running = cfg.wantRun
        b.btnToggle.text = if (running) "停止中枢" else "启动中枢"
        b.tvStatus.text = if (running) "中枢运行中" else "未启动"

        val engineStatuses = runCatching { Engine.get(this).statuses() }.getOrDefault(emptyList())
        val enabled = engineStatuses.count { it.enabled }
        val ok = engineStatuses.count { it.enabled && it.lastError == null && it.lastOk > 0 }
        b.tvSub.text = if (running) {
            "$ok/$enabled 个源正常 · 共 ${store.count()} 条历史"
        } else {
            "点上面按钮启动本地中枢"
        }

        renderSources(engineStatuses)
        renderHistory()
    }

    private fun renderSources(statuses: List<dev.watchhub.app.hub.SourceStatus>) {
        b.llSources.removeAllViews()
        if (statuses.isEmpty()) {
            b.llSources.addView(label("没有数据源，点下面「编辑配置」添加"))
            return
        }
        for (st in statuses) {
            val item = ItemSourceBinding.inflate(LayoutInflater.from(this), b.llSources, false)
            item.tvName.text = st.name
            val last = if (st.lastCheck > 0) fmt.format(Date(st.lastCheck)) else "从未"
            val meta = buildString {
                append(st.kind)
                append(" · 每 ").append(if (st.intervalSec >= 60) "${st.intervalSec / 60}分" else "${st.intervalSec}秒")
                append(" · 上次 ").append(last)
                st.lastError?.let { append("\n⚠ ").append(it) }
                if (st.lastError == null && st.newLastRun > 0) append(" · 新增 ").append(st.newLastRun)
            }
            item.tvMeta.text = meta
            item.swEnabled.isChecked = st.enabled
            item.swEnabled.setOnCheckedChangeListener { _, v -> setEnabled(st.name, v) }
            item.btnCheck.setOnClickListener {
                Thread {
                    val n = runCatching { Engine.get(this).checkNow(st.name) }.getOrDefault(0)
                    runOnUiThread {
                        toast(if (n > 0) "${st.name}: 新增 $n 条" else "${st.name}: 无新内容")
                        refresh()
                    }
                }.start()
            }
            b.llSources.addView(item.root)
        }
    }

    private fun label(s: String) = android.widget.TextView(this).apply {
        text = s
        textSize = 12f
        setTextColor(0xFF6B7280.toInt())
        setPadding(0, 8, 0, 8)
    }

    private fun setEnabled(name: String, enabled: Boolean) {
        val list = repo.load()
        for (src in list) {
            if (src.name == name) src.raw.put("enabled", enabled)
        }
        repo.save(list)
        LogBus.post("$name → ${if (enabled) "启用" else "禁用"}")
    }

    private fun renderHistory() {
        val items = store.recent(8)
        b.tvHistory.text = if (items.isEmpty()) "（暂无）"
        else items.joinToString("\n") { "· ${it.title}  —  ${it.source}" }
    }

    private fun ensurePermissions() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openBatterySettings() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } else {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        } catch (t: Throwable) {
            toast("请手动进入：设置 → 电池 → 应用启动管理 → WatchHub")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------------- 自更新 ----------------

    /**
     * 检查更新。manual=true 用 Toast 反馈；静默检查只更新按钮/版本文案。
     * 网络在后台线程，结果回主线程。
     */
    private fun checkUpdate(manual: Boolean) {
        b.btnUpdate.isEnabled = false
        b.btnUpdate.text = "检查中…"
        Thread {
            val url = BuildConfig.UPDATE_MANIFEST_URL.ifBlank { Updater.DEFAULT_MANIFEST_URL }
            val result = Updater.check(url, Updater.currentVersionCode())
            runOnUiThread {
                b.btnUpdate.isEnabled = true
                when (result) {
                    is UpdateCheck.Available -> {
                        pendingUpdate = result.manifest
                        b.btnUpdate.text = "下载并安装 " + result.manifest.versionName
                        b.tvVersion.text = buildString {
                            append("当前 ")
                            append(Updater.currentVersionName())
                            append(" → ")
                            append(result.manifest.versionName)
                            if (result.manifest.changelog.isNotBlank()) {
                                append("\n")
                                append(result.manifest.changelog.take(400))
                            }
                        }
                        if (manual) toast("发现新版本 " + result.manifest.versionName)
                    }
                    is UpdateCheck.UpToDate -> {
                        pendingUpdate = null
                        b.btnUpdate.text = "检查更新"
                        b.tvVersion.text = "版本 " + Updater.currentVersionName() + " · 已是最新"
                        if (manual) toast("已是最新版本")
                    }
                    is UpdateCheck.Unsupported -> {
                        pendingUpdate = null
                        b.btnUpdate.text = "检查更新"
                        b.tvVersion.text = "版本 " + Updater.currentVersionName() +
                            " · 新版本 " + result.manifest.versionName + " 需要更高系统版本"
                        if (manual) toast("新版本要求更高的 Android 版本")
                    }
                    is UpdateCheck.Failed -> {
                        pendingUpdate = null
                        b.btnUpdate.text = "检查更新"
                        b.tvVersion.text = "版本 " + Updater.currentVersionName() + " · 检查失败"
                        if (manual) toast("检查失败：" + result.reason)
                    }
                }
            }
        }.start()
    }

    private fun downloadAndInstall(m: UpdateManifest) {
        b.btnUpdate.isEnabled = false
        b.btnUpdate.text = "下载中…"
        LogBus.post("开始下载 " + m.versionName)
        Updater.download(this, m) { ok, msg ->
            runOnUiThread {
                b.btnUpdate.isEnabled = true
                LogBus.post("下载结果: " + msg)
                if (ok) {
                    b.btnUpdate.text = "安装 " + m.versionName
                    toast("下载完成，正在打开安装器")
                    Updater.install(this).onFailure { toast("拉起安装器失败：" + it.message) }
                } else {
                    b.btnUpdate.text = "重新下载 " + m.versionName
                    toast(msg)
                }
            }
        }
    }
}

private object Settings {
    const val ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS =
        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
    const val ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS =
        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
}
