package dev.watchhub.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.watchhub.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var cfg: Config
    private var running = false

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Toast.makeText(this, "没有通知权限，手表收不到任何东西", Toast.LENGTH_LONG).show()
        }

    private val logListener: (List<String>) -> Unit = { lines ->
        b.tvLog.text = lines.takeLast(40).joinToString("\n").ifBlank { "（暂无）" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        cfg = Config(this)

        b.etUrl.setText(cfg.baseUrl)
        b.etToken.setText(cfg.token)
        b.swStartOnBoot.isChecked = cfg.startOnBoot

        b.btnSave.setOnClickListener {
            save()
            toast("已保存")
        }
        b.btnTest.setOnClickListener {
            save()
            b.btnTest.isEnabled = false
            b.tvStatus.text = "正在测试…"
            Thread {
                val (ok, msg) = SseClient.probe(cfg)
                runOnUiThread {
                    b.btnTest.isEnabled = true
                    b.tvStatus.text = (if (ok) "连接正常：$msg" else "连接失败：$msg")
                    LogBus.post("probe -> $ok $msg")
                }
            }.start()
        }
        b.btnToggle.setOnClickListener {
            save()
            if (running) {
                SseService.stop(this)
                setWantRun(false)
                running = false
                b.btnToggle.text = "开始连接"
                b.tvStatus.text = "已停止"
            } else {
                ensurePermissions()
                SseService.start(this)
                setWantRun(true)
                running = true
                b.btnToggle.text = "停止连接"
                b.tvStatus.text = "正在连接…"
            }
        }
        b.btnTestNotif.setOnClickListener {
            Notifier.show(
                this,
                Notif(
                    id = System.currentTimeMillis() % 1_000_000,
                    ts = "",
                    title = "测试通知",
                    body = "如果你在手表上看到这条，整条链路就通了 🎉",
                    detail = "",
                    priority = "high",
                    tags = listOf("test"),
                    url = "",
                    source = "local",
                )
            )
        }

        b.tvStatus.setOnClickListener { openBatterySettings() }
    }

    override fun onStart() {
        super.onStart()
        LogBus.addListener(logListener)
        running = isServiceRunning()
        b.btnToggle.text = if (running) "停止连接" else "开始连接"
        b.tvStatus.text = if (running) "服务运行中" else "未连接"
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canPost(this)) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStop() {
        super.onStop()
        LogBus.removeListener(logListener)
    }

    private fun save() {
        cfg.baseUrl = b.etUrl.text?.toString().orEmpty()
        cfg.token = b.etToken.text?.toString().orEmpty()
        cfg.startOnBoot = b.swStartOnBoot.isChecked
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
            toast("打不开电池设置，请手动进入系统设置 → 电池 → WatchHub")
        }
    }

    /** 用「用户希望它在跑」的标记代替 ActivityManager 反射，重启后也能反映真实意图。 */
    private fun isServiceRunning(): Boolean =
        getSharedPreferences("watchhub", MODE_PRIVATE).getBoolean("want_run", false)

    private fun setWantRun(v: Boolean) =
        getSharedPreferences("watchhub", MODE_PRIVATE).edit().putBoolean("want_run", v).apply()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
