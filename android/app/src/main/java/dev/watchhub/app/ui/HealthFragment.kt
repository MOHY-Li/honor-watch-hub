package dev.watchhub.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.watchhub.app.App
import dev.watchhub.app.R
import dev.watchhub.app.core.model.HealthRule
import dev.watchhub.app.core.model.MetricType
import dev.watchhub.app.core.model.Priority
import dev.watchhub.app.core.model.RuleOp
import dev.watchhub.app.databinding.FragmentHealthBinding
import dev.watchhub.app.ui.adapters.HealthRuleAdapter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 健康页（2.6.0 起是第一页）：身体指标卡（AI 分析/报告）+ 健康规则区块。
 *
 * 2.5.0 时指标在首页、规则在任务页；本次把身体域收敛到一页。
 * 通过 Container 的变更总线即时刷新（写操作后其他页面也会收到）。
 */
class HealthFragment : Fragment() {

    private var _binding: FragmentHealthBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ruleAdapter: HealthRuleAdapter
    private var analyzing = false
    private val ctx get() = requireContext().applicationContext
    private val onChanged: () -> Unit = { if (!isDetached) refresh() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHealthBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ruleAdapter = HealthRuleAdapter(
            onEnabled = { row, enabled ->
                runCatching { HubUi.setHealthRuleEnabled(ctx, row.id, enabled) }
                refresh()
            },
            onEdit = { row ->
                HubUi.healthRuleById(ctx, row.id)?.let { showRuleDialog(it) }
            },
            onDelete = { row -> confirmDeleteRule(row) },
            onNew = { showRuleDialog(null) },
        )
        binding.rulesList.layoutManager = LinearLayoutManager(requireContext())
        binding.rulesList.adapter = ruleAdapter

        binding.btnAi.setOnClickListener { runAnalysis() }
        binding.btnReports.setOnClickListener { showReports() }
    }

    override fun onResume() {
        super.onResume()
        App.container(ctx).changes.register(onChanged)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        App.container(ctx).changes.unregister(onChanged)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        binding.rulesList.adapter = null
        _binding = null
    }

    private fun refresh() {
        val b = _binding ?: return
        val home = runCatching { HubUi.home(ctx) }.getOrNull() ?: return
        renderMetrics(home.metricRows, home.healthStatusText)

        val rules = runCatching { HubUi.healthRuleRows(ctx) }.getOrElse { emptyList() }
        val rows = ArrayList<HealthRuleAdapter.Row>()
        rows.add(HealthRuleAdapter.Row.Header)
        if (rules.isEmpty()) rows.add(HealthRuleAdapter.Row.Empty)
        rules.forEach { rows.add(HealthRuleAdapter.Row.Item(it)) }
        rows.add(HealthRuleAdapter.Row.NewEntry)
        ruleAdapter.submitList(rows)
    }

    /** 指标列表：每项一行（名称 / 最新值 / 采集时间）。 */
    private fun renderMetrics(metricRows: List<HubUi.MetricRow>, statusText: String) {
        val b = _binding ?: return
        val c = requireContext()
        b.metricsContainer.removeAllViews()

        if (statusText.isNotEmpty()) {
            b.metricsStatus.text = statusText
            b.metricsStatus.visibility = View.VISIBLE
        } else if (metricRows.isEmpty()) {
            b.metricsStatus.text = getString(R.string.health_metrics_empty)
            b.metricsStatus.visibility = View.VISIBLE
        } else {
            b.metricsStatus.visibility = View.GONE
        }

        metricRows.forEach { row ->
            val line = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            line.addView(TextView(c).apply {
                text = row.label
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            line.addView(TextView(c).apply {
                text = row.valueText
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            })
            line.addView(TextView(c).apply {
                text = row.timeText
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(
                    if (row.stale) {
                        c.getColor(R.color.health_warn)
                    } else {
                        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
                    },
                )
                setPadding(dp(8), 0, 0, 0)
            })
            b.metricsContainer.addView(line)
        }
    }

    // ---------------- AI 分析与报告 ----------------

    /** 手动 AI 分析：后台线程跑（模型生成最长约 2 分钟），完成弹报告。 */
    private fun runAnalysis() {
        if (analyzing) return
        analyzing = true
        toast(getString(R.string.ai_analyzing))
        val appCtx = ctx
        Thread({
            val result = HubUi.analyzeNow(appCtx)
            handler.post {
                analyzing = false
                if (!isAdded) return@post
                result
                    .onSuccess { report -> showReport(report.createdAt, report.content) }
                    .onFailure {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.subs_add_failed_title)
                            .setMessage(it.message ?: "分析失败")
                            .setPositiveButton(R.string.ok, null)
                            .show()
                    }
                refresh()
            }
        }, "watchhub-ai-manual").start()
    }

    private fun showReports() {
        val rows = runCatching { HubUi.reportRows(ctx) }.getOrElse { emptyList() }
        if (rows.isEmpty()) {
            toast(getString(R.string.health_reports_empty))
            return
        }
        val labels = rows.map { "${it.timeText} · ${it.preview}" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.health_reports)
            .setItems(labels) { _, which ->
                HubUi.reportById(ctx, rows[which].id)?.let { showReport(it.createdAt, it.content) }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showReport(createdAt: Long, content: String) {
        if (!isAdded) return
        val time = Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val view = TextView(requireContext()).apply {
            text = content
            setPadding(dp(20), dp(16), dp(20), dp(8))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(requireContext()).apply { addView(view) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.ai_report_title, time))
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ---------------- 规则表单（自 2.5.0 任务页迁入） ----------------

    private fun confirmDeleteRule(row: HubUi.HealthRuleRow) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.health_rule_delete_confirm, row.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                runCatching { HubUi.deleteHealthRule(ctx, row.id) }
                    .onFailure { toast("删除失败：" + (it.message ?: it.javaClass.simpleName)) }
                refresh()
            }
            .show()
    }

    private fun showRuleDialog(existing: HealthRule?) {
        val ui = requireContext()

        fun outlined(hint: String): TextInputLayout = TextInputLayout(ui).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            this.hint = hint
            setPadding(0, dp(4), 0, dp(4))
        }

        val name = TextInputEditText(ui).apply { setText(existing?.name ?: ""); maxLines = 1 }
        val nameLayout = outlined(getString(R.string.health_rule_name)).apply { addView(name) }

        val metricLabels = MetricType.entries.map { it.label }.toTypedArray()
        val metric = MaterialAutoCompleteTextView(ui).apply {
            setSimpleItems(metricLabels)
            setText((existing?.metric ?: MetricType.HEART_RATE).label, false)
            inputType = InputType.TYPE_NULL
            setOnClickListener { showDropDown() }
        }
        val metricLayout = outlined(getString(R.string.health_rule_metric)).apply { addView(metric) }

        val opLabels = arrayOf(getString(R.string.health_rule_op_above), getString(R.string.health_rule_op_below))
        val op = MaterialAutoCompleteTextView(ui).apply {
            setSimpleItems(opLabels)
            setText(if (existing?.op == RuleOp.BELOW) opLabels[1] else opLabels[0], false)
            inputType = InputType.TYPE_NULL
            setOnClickListener { showDropDown() }
        }
        val opLayout = outlined(getString(R.string.health_rule_op)).apply { addView(op) }

        fun numberInput(initial: String): TextInputEditText = TextInputEditText(ui).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(initial)
            maxLines = 1
        }

        val threshold = numberInput(existing?.threshold?.let { trimZero(it) } ?: "100")
        val thresholdLayout = outlined(getString(R.string.health_rule_threshold)).apply { addView(threshold) }
        val sustained = numberInput((existing?.sustainedMin ?: 15).toString())
        val sustainedLayout = outlined(getString(R.string.health_rule_sustained)).apply { addView(sustained) }
        val cooldown = numberInput((existing?.cooldownMin ?: 60).toString())
        val cooldownLayout = outlined(getString(R.string.health_rule_cooldown)).apply { addView(cooldown) }

        val priorityLabels = arrayOf("高", "普通", "低")
        val priority = MaterialAutoCompleteTextView(ui).apply {
            setSimpleItems(priorityLabels)
            setText(
                when (existing?.priority) {
                    Priority.HIGH -> "高"
                    Priority.LOW -> "低"
                    else -> "普通"
                },
                false,
            )
            inputType = InputType.TYPE_NULL
            setOnClickListener { showDropDown() }
        }
        val priorityLayout = outlined(getString(R.string.health_rule_priority)).apply { addView(priority) }

        val container = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        listOf(
            nameLayout, metricLayout, opLayout, thresholdLayout,
            sustainedLayout, cooldownLayout, priorityLayout,
        ).forEach { container.addView(it) }

        var dialog: androidx.appcompat.app.AlertDialog? = null
        val dlg = MaterialAlertDialogBuilder(ui)
            .setTitle(
                if (existing == null) getString(R.string.health_rule_new) else getString(R.string.health_rule_edit),
            )
            .setView(ScrollView(ui).apply { addView(container) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog = dlg
        dlg.setOnShowListener {
            dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val ruleName = name.text?.toString()?.trim().orEmpty()
                if (ruleName.isBlank()) {
                    nameLayout.error = getString(R.string.subs_url_required)
                    return@setOnClickListener
                }
                val metricType = MetricType.entries.firstOrNull { it.label == metric.text?.toString() }
                    ?: MetricType.HEART_RATE
                val thresholdValue = threshold.text?.toString()?.trim()?.toDoubleOrNull()
                if (thresholdValue == null || !metricType.isValid(thresholdValue)) {
                    thresholdLayout.error = getString(
                        R.string.health_rule_threshold_range,
                        trimZero(metricType.min), trimZero(metricType.max), metricType.unit,
                    )
                    return@setOnClickListener
                }
                val rule = HealthRule(
                    id = existing?.id ?: 0,
                    name = ruleName,
                    metric = metricType,
                    op = if (op.text?.toString() == opLabels[1]) RuleOp.BELOW else RuleOp.ABOVE,
                    threshold = thresholdValue,
                    sustainedMin = sustained.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 15,
                    cooldownMin = cooldown.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 60,
                    priority = Priority.parse(priority.text?.toString()),
                )
                HubUi.saveHealthRule(ctx, rule)
                    .onSuccess {
                        toast(getString(R.string.subs_added, ruleName))
                        dialog?.dismiss()
                        refresh()
                    }
                    .onFailure { toast(it.message ?: "保存失败") }
            }
        }
        dlg.show()
    }

    private fun trimZero(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }
}
