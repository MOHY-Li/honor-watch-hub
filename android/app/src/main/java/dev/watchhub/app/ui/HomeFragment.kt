package dev.watchhub.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.watchhub.app.R
import dev.watchhub.app.core.model.Alert
import dev.watchhub.app.core.model.DeliveryState
import dev.watchhub.app.databinding.FragmentHomeBinding
import dev.watchhub.app.health.HealthLevel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 首页：中枢开关、今日进度、下一次提醒、体检摘要、数据源健康数、最近推送。 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())
    private var updating = false
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.hubSwitch.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            val ctx = requireContext().applicationContext
            runCatching {
                if (checked) HubUi.start(ctx) else HubUi.stop(ctx)
            }.onFailure {
                toast("操作失败：" + (it.message ?: it.javaClass.simpleName))
            }
            refresh()
        }
        binding.healthCard.setOnClickListener {
            startActivity(Intent(requireContext(), HealthActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        restartTicker()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            handler.removeCallbacks(ticker)
        } else {
            refresh()
            restartTicker()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(ticker)
        _binding = null
    }

    private fun restartTicker() {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, REFRESH_MS)
    }

    private fun refresh() {
        val ctx = requireContext().applicationContext
        val home = runCatching { HubUi.home(ctx) }.getOrNull() ?: return
        val b = _binding ?: return
        updating = true

        b.hubSwitch.isChecked = home.wantRun
        b.hubDesc.text = if (home.wantRun && home.running) {
            getString(R.string.home_hub_running)
        } else if (home.wantRun) {
            "中枢已开启，但服务心跳未更新（可能被系统杀了），点开关重启"
        } else {
            getString(R.string.home_hub_stopped)
        }

        val total = home.todayOpen + home.todayDone
        b.todayProgress.progress = if (total > 0) home.todayDone * 100 / total else 0
        b.todayText.text = if (total > 0) {
            "已完成 ${home.todayDone} / ${total}，还剩 ${home.todayOpen} 条待办"
        } else {
            getString(R.string.home_progress_none)
        }

        b.nextTitle.text = home.nextTitle ?: getString(R.string.home_next_none)
        b.nextTime.text = home.nextTimeText.orEmpty()

        val worst = home.healthWorst
        val fails = home.health.count { it.level == HealthLevel.FAIL }
        val warns = home.health.count { it.level == HealthLevel.WARN }
        b.healthSummary.text = when (worst) {
            HealthLevel.FAIL -> "异常：$fails 项需要处理"
            HealthLevel.WARN -> "需注意：$warns 项建议检查"
            HealthLevel.OK -> "链路正常"
            HealthLevel.INFO -> "暂无可判断项"
        }
        b.healthSummary.setTextColor(colorForLevel(worst))
        b.sourcesText.text = getString(R.string.home_sources_value, home.sourcesOk, home.sourcesTotal)

        renderRecent(home.recentAlerts)
        updating = false
    }

    private fun renderRecent(alerts: List<Alert>) {
        val container = binding.recentContainer
        container.removeAllViews()
        if (alerts.isEmpty()) {
            container.addView(captionView(getString(R.string.home_recent_none)))
            return
        }
        alerts.forEach { alert ->
            val title = android.widget.TextView(requireContext()).apply {
                text = alert.title
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnSurface,
                ))
                setPadding(0, dp(6), 0, 0)
                isClickable = true
                setOnClickListener { showAlert(alert) }
            }
            container.addView(title)
            val trailing = " · " + when (alert.state) {
                DeliveryState.PENDING -> "待送达"
                DeliveryState.DELIVERED -> "已送达"
                DeliveryState.FAILED -> "送达失败"
            }
            container.addView(captionView(formatTime(alert.at) + trailing + if (alert.source.isNotBlank()) " · ${alert.source}" else ""))
        }
    }

    private fun showAlert(alert: Alert) {
        val lines = ArrayList<String>()
        if (alert.body.isNotBlank()) lines.add(alert.body)
        if (alert.detail.isNotBlank()) lines.add(alert.detail)
        if (alert.source.isNotBlank()) lines.add("来源：${alert.source}")
        if (alert.at > 0) lines.add("时间：" + formatTime(alert.at))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(alert.title.ifBlank { getString(R.string.alert_detail_title) })
            .setMessage(lines.joinToString("\n").ifBlank { "（无详情）" })
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun captionView(text: String): android.widget.TextView =
        android.widget.TextView(requireContext()).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(com.google.android.material.color.MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorOnSurfaceVariant,
            ))
            setPadding(0, dp(2), 0, 0)
        }

    private fun colorForLevel(level: HealthLevel): Int = when (level) {
        HealthLevel.OK -> requireContext().getColor(R.color.health_ok)
        HealthLevel.WARN -> requireContext().getColor(R.color.health_warn)
        HealthLevel.FAIL -> requireContext().getColor(R.color.health_fail)
        HealthLevel.INFO -> requireContext().getColor(R.color.health_info)
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "--"
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) {
        android.widget.Toast.makeText(requireContext(), text, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REFRESH_MS = 5_000L
    }
}
