package dev.watchhub.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.watchhub.app.R
import dev.watchhub.app.core.model.HealthRule
import dev.watchhub.app.core.model.MetricType
import dev.watchhub.app.core.model.Priority
import dev.watchhub.app.core.model.RuleOp
import dev.watchhub.app.databinding.FragmentTasksBinding
import dev.watchhub.app.ui.adapters.HealthRuleAdapter
import dev.watchhub.app.ui.adapters.ReminderAdapter
import dev.watchhub.app.ui.adapters.TaskAdapter

/** 任务页：提醒规则区块 + 健康规则区块 + 按已过期/今天/之后/最近完成分组的任务列表。 */
class TasksFragment : Fragment() {

    private var _binding: FragmentTasksBinding? = null
    private val binding get() = _binding!!
    private lateinit var taskAdapter: TaskAdapter
    private lateinit var reminderAdapter: ReminderAdapter
    private lateinit var healthRuleAdapter: HealthRuleAdapter
    private val ctx get() = requireContext().applicationContext

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTasksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        taskAdapter = TaskAdapter(
            onDone = { row -> runAndRefresh { HubUi.completeTask(ctx, row.id) } },
            onSkip = { row -> runAndRefresh { HubUi.skipTask(ctx, row.id) } },
            onSnooze = { row -> runAndRefresh { HubUi.snoozeTask(ctx, row.id, 10) } },
        )
        reminderAdapter = ReminderAdapter(
            onEnabled = { row, enabled ->
                runCatching { HubUi.setReminderEnabled(ctx, row.id, enabled) }
                refresh()
            },
            onEdit = { row ->
                startActivity(
                    Intent(requireContext(), TaskEditActivity::class.java)
                        .putExtra(TaskEditActivity.EXTRA_ID, row.id),
                )
            },
            onDelete = { row -> confirmDelete(row) },
        )
        healthRuleAdapter = HealthRuleAdapter(
            onEnabled = { row, enabled ->
                runCatching { HubUi.setHealthRuleEnabled(ctx, row.id, enabled) }
                refresh()
            },
            onEdit = { row ->
                HubUi.healthRuleById(ctx, row.id)?.let { showRuleDialog(it) }
            },
            onDelete = { row -> confirmDeleteHealthRule(row) },
            onNew = { showRuleDialog(null) },
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        // 提醒规则在前，健康规则居中，任务列表在后
        binding.list.adapter = ConcatAdapter(reminderAdapter, healthRuleAdapter, taskAdapter)
        binding.btnNew.setOnClickListener {
            startActivity(Intent(requireContext(), TaskEditActivity::class.java))
        }
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
        binding.list.adapter = null
        _binding = null
    }

    private fun refresh() {
        if (_binding == null) return

        val reminders = runCatching { HubUi.reminderRows(ctx) }.getOrElse { emptyList() }
        val reminderRows = ArrayList<ReminderAdapter.Row>()
        reminderRows.add(ReminderAdapter.Row.Header)
        if (reminders.isEmpty()) {
            reminderRows.add(ReminderAdapter.Row.Empty)
        } else {
            reminders.forEach { row ->
                // ReminderAdapter 只渲染 schedule 行，nextText 拼进去一起展示
                reminderRows.add(
                    ReminderAdapter.Row.Item(
                        row.copy(scheduleText = row.scheduleText + " · " + row.nextText),
                    ),
                )
            }
        }

        val healthRules = runCatching { HubUi.healthRuleRows(ctx) }.getOrElse { emptyList() }
        val healthRows = ArrayList<HealthRuleAdapter.Row>()
        healthRows.add(HealthRuleAdapter.Row.Header)
        if (healthRules.isEmpty()) healthRows.add(HealthRuleAdapter.Row.Empty)
        healthRules.forEach { healthRows.add(HealthRuleAdapter.Row.Item(it)) }
        healthRows.add(HealthRuleAdapter.Row.NewEntry)

        val sections = runCatching { HubUi.taskSections(ctx) }.getOrElse { emptyList() }
        val tasks = ArrayList<TaskAdapter.Row>()
        sections.forEach { section ->
            tasks.add(TaskAdapter.Row.Section(section.title))
            section.rows.forEach { tasks.add(TaskAdapter.Row.Task(it)) }
        }

        reminderAdapter.submitList(reminderRows)
        healthRuleAdapter.submitList(healthRows)
        taskAdapter.submitList(tasks)
        binding.empty.visibility =
            if (tasks.isEmpty() && reminders.isEmpty() && healthRules.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun runAndRefresh(block: () -> Unit) {
        runCatching(block).onFailure {
            toast("操作失败：" + (it.message ?: it.javaClass.simpleName))
        }
        refresh()
    }

    private fun confirmDelete(row: HubUi.ReminderRow) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.task_delete_confirm, row.name))
            .setMessage(R.string.task_delete_confirm_desc)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                runAndRefresh { HubUi.deleteReminder(ctx, row.id) }
            }
            .show()
    }

    private fun confirmDeleteHealthRule(row: HubUi.HealthRuleRow) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.health_rule_delete_confirm, row.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                runAndRefresh { HubUi.deleteHealthRule(ctx, row.id) }
            }
            .show()
    }

    // ---------------- 健康规则表单 ----------------

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
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }
}
