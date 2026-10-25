package dev.watchhub.app.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.color.MaterialColors
import dev.watchhub.app.R
import dev.watchhub.app.databinding.FragmentHomeBinding
import dev.watchhub.app.health.HealthLevel

/** 首页：中枢开关、下一次提醒、体检摘要、数据源状态列表。 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())
    private var updating = false
    private var analyzing = false
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
        binding.btnAi.setOnClickListener { runAnalysis() }
        binding.btnReports.setOnClickListener { showReports() }
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

        renderSourceStates(home.sourceStates)
        renderMetrics(home)

        updating = false
    }

    /** 身体指标卡：最新值 + 时间；未接入时显示状态文案。 */
    private fun renderMetrics(home: HubUi.Home) {
        val b = _binding ?: return
        val ctx = requireContext()
        b.metricsContainer.removeAllViews()

        if (home.healthStatusText.isNotEmpty()) {
            b.metricsStatus.text = home.healthStatusText
            b.metricsStatus.visibility = View.VISIBLE
        } else {
            b.metricsStatus.visibility = View.GONE
        }

        if (home.metricRows.isEmpty() && home.healthStatusText.isEmpty()) {
            b.metricsStatus.text = getString(R.string.health_metrics_empty)
            b.metricsStatus.visibility = View.VISIBLE
        }

        home.metricRows.forEach { row ->
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            line.addView(TextView(ctx).apply {
                text = row.label
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            line.addView(TextView(ctx).apply {
                text = row.valueText
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            })
            line.addView(TextView(ctx).apply {
                text = row.timeText
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(
                    if (row.stale) {
                        requireContext().getColor(R.color.health_warn)
                    } else {
                        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
                    },
                )
                setPadding(dp(8), 0, 0, 0)
            })
            b.metricsContainer.addView(line)
        }
    }

    /** 手动 AI 分析：后台线程跑（模型生成最长约 2 分钟），完成弹报告。 */
    private fun runAnalysis() {
        if (analyzing) return
        analyzing = true
        toast(getString(R.string.ai_analyzing))
        val appCtx = requireContext().applicationContext
        Thread({
            val result = HubUi.analyzeNow(appCtx)
            handler.post {
                analyzing = false
                if (!isAdded) return@post
                result
                    .onSuccess { report -> showReport(report.createdAt, report.content) }
                    .onFailure {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.subs_add_failed_title)
                            .setMessage(it.message ?: "分析失败")
                            .setPositiveButton(R.string.ok, null)
                            .show()
                    }
            }
        }, "watchhub-ai-manual").start()
    }

    private fun showReports() {
        val rows = runCatching { HubUi.reportRows(requireContext().applicationContext) }
            .getOrElse { emptyList() }
        if (rows.isEmpty()) {
            toast(getString(R.string.health_reports_empty))
            return
        }
        val labels = rows.map { "${it.timeText} · ${it.preview}" }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.health_reports)
            .setItems(labels) { _, which ->
                val id = rows[which].id
                val appCtx = requireContext().applicationContext
                HubUi.reportById(appCtx, id)?.let { showReport(it.createdAt, it.content) }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showReport(createdAt: Long, content: String) {
        if (!isAdded) return
        val time = java.time.Instant.ofEpochMilli(createdAt)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val view = TextView(requireContext()).apply {
            text = content
            setPadding(dp(20), dp(16), dp(20), dp(8))
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(requireContext()).apply { addView(view) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.ai_report_title, time))
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    /** 数据源状态列表（后端已按异常置顶排序，这里直接渲染）。 */
    private fun renderSourceStates(states: List<HubUi.SourceStatusRow>) {
        val b = _binding ?: return
        val ctx = requireContext()
        b.sourcesContainer.removeAllViews()

        if (states.isEmpty()) {
            b.sourcesContainer.addView(TextView(ctx).apply {
                text = getString(R.string.home_sources_empty)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(
                    MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant),
                )
                setPadding(0, dp(8), 0, 0)
            })
            return
        }

        states.forEach { row ->
            val color = sourceLevelColor(row.level, b.sourcesContainer)
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            line.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) }
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_dot)
                backgroundTintList = ColorStateList.valueOf(color)
            })
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(ctx).apply {
                text = row.name
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            texts.addView(TextView(ctx).apply {
                text = row.statusText
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(color)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            line.addView(texts)
            line.addView(TextView(ctx).apply {
                text = row.kind.uppercase(java.util.Locale.ROOT)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
                setTextColor(
                    MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant),
                )
                setPadding(dp(8), 0, 0, 0)
            })
            b.sourcesContainer.addView(line)
        }
    }

    private fun colorForLevel(level: HealthLevel): Int = when (level) {
        HealthLevel.OK -> requireContext().getColor(R.color.health_ok)
        HealthLevel.WARN -> requireContext().getColor(R.color.health_warn)
        HealthLevel.FAIL -> requireContext().getColor(R.color.health_fail)
        HealthLevel.INFO -> requireContext().getColor(R.color.health_info)
    }

    /** 数据源状态色：INFO 用灰色（主题 onSurfaceVariant），其余用健康色。 */
    private fun sourceLevelColor(level: HealthLevel, anchor: View): Int = when (level) {
        HealthLevel.OK -> requireContext().getColor(R.color.health_ok)
        HealthLevel.WARN -> requireContext().getColor(R.color.health_warn)
        HealthLevel.FAIL -> requireContext().getColor(R.color.health_fail)
        HealthLevel.INFO -> MaterialColors.getColor(
            anchor, com.google.android.material.R.attr.colorOnSurfaceVariant,
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) {
        android.widget.Toast.makeText(requireContext(), text, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REFRESH_MS = 15_000L
    }
}
