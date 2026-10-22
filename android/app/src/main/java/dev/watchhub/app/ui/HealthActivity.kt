package dev.watchhub.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import dev.watchhub.app.R
import dev.watchhub.app.databinding.ActivityHealthBinding
import dev.watchhub.app.health.HealthAction
import dev.watchhub.app.health.HealthItem
import dev.watchhub.app.ui.adapters.HealthAdapter

/**
 * 链路体检。
 *
 * 点击带 action 的项先交给 [HubUi.performAction]；返回 false 表示需要运行时权限，
 * 由 Activity 自己申请（通知权限 / 日历权限），申请完刷新列表。
 * minSdk 24：API 33 以下没有 POST_NOTIFICATIONS，只能跳系统通知设置。
 */
class HealthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHealthBinding
    private lateinit var adapter: HealthAdapter

    private val calendarPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.WRITE_CALENDAR] == true ||
                result[Manifest.permission.READ_CALENDAR] == true
            if (!granted) toast("日历权限未授予")
            refresh()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast(getString(R.string.notification_denied))
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHealthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        adapter = HealthAdapter { item -> onItemClick(item) }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.btnRefresh.setOnClickListener { refresh() }
        binding.btnTest.setOnClickListener { sendTest() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val items = runCatching { HubUi.health(this) }.getOrElse {
            toast("读取体检结果失败：${it.message}")
            emptyList()
        }
        adapter.submitList(items)
    }

    private fun onItemClick(item: HealthItem) {
        val action = item.action ?: return
        val handled = runCatching { HubUi.performAction(this, action) }.getOrDefault(false)
        if (handled) {
            refresh()
            return
        }
        when (action) {
            HealthAction.NOTIFICATION_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // API 24-32 没有运行时通知权限，只能引导去系统通知设置
                    runCatching { HubUi.performAction(this, HealthAction.APP_NOTIFICATION_SETTINGS) }
                    toast(getString(R.string.health_need_permission))
                }
            }
            HealthAction.CALENDAR_PERMISSION -> {
                calendarPermissionLauncher.launch(
                    arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                )
            }
            else -> Unit
        }
    }

    private fun sendTest() {
        val ok = runCatching { HubUi.testNotification(this) }.getOrDefault(false)
        if (ok) {
            toast(getString(R.string.test_sent))
        } else if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            toast(getString(R.string.test_failed))
        }
        refresh()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
