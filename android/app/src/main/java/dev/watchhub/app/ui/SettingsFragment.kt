package dev.watchhub.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.watchhub.app.R
import dev.watchhub.app.databinding.FragmentSettingsBinding
import dev.watchhub.app.update.UpdateCheck
import dev.watchhub.app.update.UpdateManifest
import dev.watchhub.app.update.Updater

/** 设置页：开关、体检入口、日志、版本与自更新。 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())
    private val ctx get() = requireContext().applicationContext
    private var updating = false
    private var checkingUpdate = false
    private var pendingCalendarEnable = false

    private val calendarPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val want = pendingCalendarEnable
            pendingCalendarEnable = false
            if (want) {
                val granted = result[Manifest.permission.WRITE_CALENDAR] == true ||
                    ContextCompat.checkSelfPermission(
                        requireContext(), Manifest.permission.WRITE_CALENDAR,
                    ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    HubUi.setCalendarEnabled(ctx, true)
                } else {
                    HubUi.setCalendarEnabled(ctx, false)
                    toast(getString(R.string.calendar_denied))
                }
            }
            refresh()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast(getString(R.string.notification_denied))
            refresh()
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.switchBoot.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            HubUi.setStartOnBoot(ctx, checked)
        }
        binding.switchCalendar.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            if (checked) {
                if (hasCalendarPermission()) {
                    HubUi.setCalendarEnabled(ctx, true)
                } else {
                    pendingCalendarEnable = true
                    calendarPermissionLauncher.launch(
                        arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                    )
                }
            } else {
                HubUi.setCalendarEnabled(ctx, false)
            }
        }
        binding.cbMirror.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            HubUi.setMirrorConfirmed(ctx, checked)
        }

        binding.btnHealth.setOnClickListener {
            startActivity(Intent(requireContext(), HealthActivity::class.java))
        }
        binding.btnLogs.setOnClickListener { showLogs() }
        binding.btnTest.setOnClickListener { sendTestNotification() }
        binding.btnUpdate.setOnClickListener { checkUpdate() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        _binding = null
    }

    private fun refresh() {
        val b = _binding ?: return
        val snapshot = runCatching { HubUi.settings(ctx) }.getOrNull() ?: return
        updating = true
        b.switchBoot.isChecked = snapshot.startOnBoot
        b.switchCalendar.isChecked = snapshot.calendarEnabled
        b.cbMirror.isChecked = snapshot.mirrorConfirmed
        b.version.text = "WatchHub ${HubUi.versionName()} (${HubUi.versionCode()})"
        updating = false
    }

    private fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun showLogs() {
        val text = HubUi.logs().joinToString("\n").ifBlank { getString(R.string.logs_empty) }
        val view = TextView(requireContext()).apply {
            this.text = text
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setPadding(16, 16, 16, 16)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(requireContext()).apply { addView(view) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_logs)
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.logs_clear) { _, _ ->
                HubUi.clearLogs()
                toast("日志已清空")
            }
            .show()
    }

    private fun sendTestNotification() {
        val ok = runCatching { HubUi.testNotification(ctx) }.getOrDefault(false)
        if (ok) {
            toast(getString(R.string.test_sent))
        } else if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            toast(getString(R.string.test_failed))
        }
        refresh()
    }

    private fun checkUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true
        toast(getString(R.string.update_checking))
        val appCtx = requireContext().applicationContext
        Thread({
            val result = runCatching { HubUi.checkUpdate() }
            handler.post {
                checkingUpdate = false
                if (!isAdded) return@post
                val check = result.getOrNull()
                if (check == null) {
                    toast("检查更新失败：" + (result.exceptionOrNull()?.message ?: "未知错误"))
                    return@post
                }
                when (check) {
                    is UpdateCheck.UpToDate -> toast(getString(R.string.update_up_to_date))
                    is UpdateCheck.Unsupported -> toast(getString(R.string.update_unsupported))
                    is UpdateCheck.Failed -> toast(getString(R.string.update_failed, check.reason.take(200)))
                    is UpdateCheck.Available -> showUpdateDialog(check.manifest)
                }
            }
        }, "watchhub-update-check").start()
    }

    private fun showUpdateDialog(manifest: UpdateManifest) {
        if (!isAdded) return
        val message = buildString {
            append("版本：${manifest.versionName}\n")
            if (manifest.sizeBytes > 0) append("大小：${manifest.sizeBytes / 1024 / 1024} MB\n")
            if (manifest.changelog.isNotBlank()) {
                append("\n")
                append(manifest.changelog.take(800))
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.update_available_title, manifest.versionName))
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.update_download) { _, _ -> downloadUpdate(manifest) }
            .show()
    }

    private fun downloadUpdate(manifest: UpdateManifest) {
        toast(getString(R.string.update_started))
        runCatching {
            HubUi.downloadUpdate(ctx, manifest) { ok, message ->
                handler.post {
                    if (!isAdded) return@post
                    if (ok) {
                        showInstallDialog(message)
                    } else {
                        toast("下载失败：$message")
                    }
                }
            }
        }.onFailure { toast("下载失败：" + (it.message ?: it.javaClass.simpleName)) }
    }

    private fun showInstallDialog(verifyMessage: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("下载完成")
            .setMessage("$verifyMessage\n\n是否立即安装？")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("安装") { _, _ -> installUpdate() }
            .show()
    }

    private fun installUpdate() {
        val result = runCatching { HubUi.installUpdate(ctx) }.getOrElse {
            toast("安装失败：" + (it.message ?: it.javaClass.simpleName))
            return
        }
        when (result) {
            is Updater.InstallResult.Started -> toast("已拉起系统安装器")
            is Updater.InstallResult.NeedPermission -> toast(result.why)
            is Updater.InstallResult.Failed -> toast("安装失败：${result.reason}")
        }
    }

    private fun toast(text: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }
}
