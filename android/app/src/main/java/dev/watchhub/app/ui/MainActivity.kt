package dev.watchhub.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import dev.watchhub.app.R
import dev.watchhub.app.core.model.DeliveryState
import dev.watchhub.app.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 主界面：底部导航 + 4 个 Fragment。
 *
 * 通知点击进来时会带 [EXTRA_ALERT_ID]，这里负责把那条提醒的详情弹出来。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ALERT_ID = "alert_id"
        private const val KEY_TAB = "selected_tab"
        private const val REQ_NOTIFICATIONS = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private var currentTag: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            switchTo(item.itemId)
            true
        }
        val restored = savedInstanceState?.getInt(KEY_TAB, R.id.nav_home) ?: R.id.nav_home
        binding.bottomNav.selectedItemId = restored
        switchTo(restored)

        handleIntentAlert(intent)
        askNotificationPermissionIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentAlert(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, binding.bottomNav.selectedItemId)
    }

    private fun switchTo(tabId: Int) {
        val tag = "tab-$tabId"
        if (tag == currentTag) return
        currentTag = tag
        val existing = supportFragmentManager.findFragmentByTag(tag)
        supportFragmentManager.beginTransaction().apply {
            supportFragmentManager.fragments.forEach { f -> if (f.tag != tag) hide(f) }
            if (existing == null) {
                add(R.id.fragment_container, newFragmentFor(tabId), tag)
            } else {
                show(existing)
            }
        }.commit()
    }

    private fun newFragmentFor(tabId: Int): Fragment = when (tabId) {
        R.id.nav_tasks -> TasksFragment()
        R.id.nav_sources -> SourcesFragment()
        R.id.nav_settings -> SettingsFragment()
        else -> HomeFragment()
    }

    private fun handleIntentAlert(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_ALERT_ID, 0) ?: 0L
        if (id <= 0) return
        intent?.removeExtra(EXTRA_ALERT_ID)
        showAlertDetail(id)
    }

    private fun showAlertDetail(id: Long) {
        val alert = runCatching { HubUi.alertById(this, id) }.getOrNull()
        if (alert == null) {
            Toast.makeText(this, "找不到这条提醒记录", Toast.LENGTH_SHORT).show()
            return
        }
        val lines = ArrayList<String>()
        lines.add("时间：" + formatTime(alert.at))
        lines.add(
            "状态：" + when (alert.state) {
                DeliveryState.PENDING -> "待送达"
                DeliveryState.DELIVERED -> "已送达"
                DeliveryState.FAILED -> "送达失败"
            }
        )
        if (alert.source.isNotBlank()) lines.add("来源：${alert.source}")
        if (alert.body.isNotBlank()) lines.add("正文：${alert.body}")
        if (alert.detail.isNotBlank()) lines.add("详情：${alert.detail}")

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(alert.title.ifBlank { "提醒详情" })
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("关闭", null)
        if (alert.url.isNotBlank()) {
            builder.setNeutralButton("打开链接") { _, _ -> openUrl(alert.url) }
        }
        builder.show()
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "打不开链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "未知"
        val zone = ZoneId.systemDefault()
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        return t.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }
}
