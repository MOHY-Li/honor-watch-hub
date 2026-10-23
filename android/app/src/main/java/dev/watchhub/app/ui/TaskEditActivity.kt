package dev.watchhub.app.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.watchhub.app.R
import dev.watchhub.app.core.model.Priority
import dev.watchhub.app.core.model.Recurrence
import dev.watchhub.app.core.model.Reminder
import dev.watchhub.app.core.model.ScheduleMode
import dev.watchhub.app.databinding.ActivityTaskEditBinding
import java.time.DayOfWeek

/**
 * 新建/编辑重复提醒。保存前做基础校验，重名由 HubUi 返回失败。
 *
 * 调度方式三选一：固定时间（Reccurrence）/ 固定间隔（分钟）/ cron 表达式。
 * 只显示当前模式对应的一组字段，另外两组隐藏。
 */
class TaskEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "reminder_id"
        private const val DEFAULT_INTERVAL_MIN = 25
        private const val MAX_INTERVAL_MIN = 1440
    }

    private lateinit var binding: ActivityTaskEditBinding
    private var reminderId: Long = 0
    private val dayChips = LinkedHashMap<DayOfWeek, Chip>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        reminderId = intent.getLongExtra(EXTRA_ID, 0L)
        binding.toolbar.setNavigationOnClickListener { finish() }
        setupDays()

        binding.radioMode.setOnCheckedChangeListener { _, _ ->
            updateModeVisibility(currentMode())
        }
        binding.etCron.doAfterTextChanged {
            if (currentMode() == ScheduleMode.CRON) updateCronPreview()
        }

        if (reminderId > 0) {
            val reminder = runCatching { HubUi.reminderById(this, reminderId) }.getOrNull()
            if (reminder == null) {
                Toast.makeText(this, "提示不存在或已删除", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            bind(reminder)
        } else {
            binding.toolbar.title = getString(R.string.task_edit_new)
            binding.rbNormal.isChecked = true
            binding.switchEnabled.isChecked = true
            binding.rbModeTimes.isChecked = true
            binding.etInterval.setText(DEFAULT_INTERVAL_MIN.toString())
            binding.btnDelete.visibility = View.GONE
            updateModeVisibility(ScheduleMode.TIMES)
        }

        binding.btnSave.setOnClickListener { save() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
    }

    private fun setupDays() {
        val labels = listOf(
            R.string.day_mon to DayOfWeek.MONDAY,
            R.string.day_tue to DayOfWeek.TUESDAY,
            R.string.day_wed to DayOfWeek.WEDNESDAY,
            R.string.day_thu to DayOfWeek.THURSDAY,
            R.string.day_fri to DayOfWeek.FRIDAY,
            R.string.day_sat to DayOfWeek.SATURDAY,
            R.string.day_sun to DayOfWeek.SUNDAY,
        )
        labels.forEach { (labelRes, day) ->
            val chip = Chip(this).apply {
                text = getString(labelRes)
                isCheckable = true
                isCheckedIconVisible = true
                isClickable = true
                chipMinHeight = resources.displayMetrics.density * 32
            }
            binding.chipDays.addView(chip)
            dayChips[day] = chip
        }
    }

    private fun currentMode(): ScheduleMode = when (binding.radioMode.checkedRadioButtonId) {
        R.id.rb_mode_interval -> ScheduleMode.INTERVAL
        R.id.rb_mode_cron -> ScheduleMode.CRON
        else -> ScheduleMode.TIMES
    }

    private fun updateModeVisibility(mode: ScheduleMode) {
        binding.groupTimes.visibility = if (mode == ScheduleMode.TIMES) View.VISIBLE else View.GONE
        binding.groupInterval.visibility = if (mode == ScheduleMode.INTERVAL) View.VISIBLE else View.GONE
        binding.groupCron.visibility = if (mode == ScheduleMode.CRON) View.VISIBLE else View.GONE
        if (mode == ScheduleMode.CRON) updateCronPreview()
    }

    private fun updateCronPreview() {
        val expr = binding.etCron.text?.toString()?.trim().orEmpty()
        binding.cronNext.text = when {
            expr.isBlank() -> getString(R.string.task_edit_cron_next_unknown)
            !HubUi.isValidCron(expr) -> getString(R.string.task_edit_cron_preview_invalid)
            else -> HubUi.cronNextText(this, expr)
                ?.let { getString(R.string.task_edit_cron_next, it) }
                ?: getString(R.string.task_edit_cron_no_next)
        }
    }

    private fun bind(reminder: Reminder) {
        binding.toolbar.title = getString(R.string.task_edit_edit)
        binding.etName.setText(reminder.name)
        binding.etName.isEnabled = true
        binding.etTitle.setText(reminder.title)
        binding.etBody.setText(reminder.body)
        binding.etTimes.setText(
            reminder.recurrence.times.sorted().joinToString(", ") { "%02d:%02d".format(it.hour, it.minute) }
        )
        reminder.recurrence.days.forEach { day -> dayChips[day]?.isChecked = true }
        when (reminder.priority) {
            Priority.HIGH -> binding.rbHigh.isChecked = true
            Priority.NORMAL -> binding.rbNormal.isChecked = true
            Priority.LOW -> binding.rbLow.isChecked = true
        }
        binding.switchEnabled.isChecked = reminder.enabled
        binding.switchCalendar.isChecked = reminder.calendarSync
        binding.btnDelete.visibility = View.VISIBLE

        binding.etInterval.setText(reminder.intervalMin.coerceIn(1, MAX_INTERVAL_MIN).toString())
        binding.etCron.setText(reminder.cron)
        binding.radioMode.check(
            when (reminder.mode) {
                ScheduleMode.TIMES -> R.id.rb_mode_times
                ScheduleMode.INTERVAL -> R.id.rb_mode_interval
                ScheduleMode.CRON -> R.id.rb_mode_cron
            }
        )
        updateModeVisibility(reminder.mode)
    }

    private fun save() {
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        val title = binding.etTitle.text?.toString()?.trim().orEmpty()
        val body = binding.etBody.text?.toString()?.trim().orEmpty()

        if (name.isBlank()) {
            binding.layoutName.error = getString(R.string.task_edit_name_required)
            return
        }
        binding.layoutName.error = null
        if (title.isBlank()) {
            binding.layoutTitle.error = getString(R.string.task_edit_title_required)
            return
        }
        binding.layoutTitle.error = null

        val mode = currentMode()
        var recurrence = Recurrence(emptyList())
        var intervalMin = DEFAULT_INTERVAL_MIN
        var cron = ""

        when (mode) {
            ScheduleMode.TIMES -> {
                val times = binding.etTimes.text?.toString().orEmpty()
                    .split(',', '，', ' ', '\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val parsed = Recurrence.parse(times, emptyList())
                if (parsed.times.isEmpty()) {
                    binding.layoutTimes.error = getString(R.string.task_edit_time_required)
                    return
                }
                binding.layoutTimes.error = null
                val days = dayChips.filterValues { it.isChecked }.keys
                recurrence = Recurrence(times = parsed.times.sorted(), days = days.toSet())
            }

            ScheduleMode.INTERVAL -> {
                val value = binding.etInterval.text?.toString()?.trim()?.toIntOrNull()
                if (value == null || value !in 1..MAX_INTERVAL_MIN) {
                    binding.layoutInterval.error = getString(R.string.task_edit_interval_required)
                    return
                }
                binding.layoutInterval.error = null
                intervalMin = value
            }

            ScheduleMode.CRON -> {
                cron = binding.etCron.text?.toString()?.trim().orEmpty()
                if (cron.isBlank()) {
                    binding.layoutCron.error = getString(R.string.task_edit_cron_required)
                    return
                }
                binding.layoutCron.error = null
                if (!HubUi.isValidCron(cron)) {
                    showError(getString(R.string.task_edit_cron_invalid))
                    return
                }
                if (HubUi.cronNextText(this, cron) == null) {
                    showError(getString(R.string.task_edit_cron_no_next))
                    return
                }
            }
        }

        val priority = when {
            binding.rbHigh.isChecked -> Priority.HIGH
            binding.rbLow.isChecked -> Priority.LOW
            else -> Priority.NORMAL
        }

        val reminder = Reminder(
            name = name,
            title = title,
            id = reminderId,
            body = body,
            priority = priority,
            recurrence = recurrence,
            enabled = binding.switchEnabled.isChecked,
            calendarSync = binding.switchCalendar.isChecked,
            mode = mode,
            intervalMin = intervalMin,
            cron = cron,
        )

        HubUi.saveReminder(this, reminder)
            .onSuccess {
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                finish()
            }
            .onFailure { error ->
                showError(error.message ?: "未知错误")
            }
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.task_edit_save_failed)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun confirmDelete() {
        if (reminderId <= 0) return
        MaterialAlertDialogBuilder(this)
            .setTitle("删除这条提醒？")
            .setMessage("删除后不再生成新任务，历史任务保留。")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                runCatching { HubUi.deleteReminder(this, reminderId) }
                    .onSuccess { finish() }
                    .onFailure {
                        Toast.makeText(this, "删除失败：${it.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .show()
    }
}
