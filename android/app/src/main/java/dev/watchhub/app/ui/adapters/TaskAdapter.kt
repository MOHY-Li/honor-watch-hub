package dev.watchhub.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.watchhub.app.core.model.TaskStatus
import dev.watchhub.app.databinding.ItemTaskBinding
import dev.watchhub.app.databinding.ItemTaskSectionBinding
import dev.watchhub.app.ui.HubUi

/** 任务列表：分组标题 + 任务行。完成/跳过/稍后只在 OPEN 行出现。 */
class TaskAdapter(
    private val onDone: (HubUi.TaskRow) -> Unit,
    private val onSkip: (HubUi.TaskRow) -> Unit,
    private val onSnooze: (HubUi.TaskRow) -> Unit,
) : ListAdapter<TaskAdapter.Row, RecyclerView.ViewHolder>(Diff) {

    sealed class Row {
        data class Section(val title: String) : Row()
        data class Task(val row: HubUi.TaskRow) : Row()
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is Row.Section -> TYPE_SECTION
        is Row.Task -> TYPE_TASK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SECTION) {
            SectionVH(ItemTaskSectionBinding.inflate(inflater, parent, false))
        } else {
            TaskVH(ItemTaskBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Section -> (holder as SectionVH).bind(row.title)
            is Row.Task -> (holder as TaskVH).bind(row.row)
        }
    }

    private class SectionVH(private val binding: ItemTaskSectionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(title: String) {
            binding.sectionTitle.text = title
        }
    }

    private inner class TaskVH(private val binding: ItemTaskBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HubUi.TaskRow) {
            binding.title.text = row.title
            binding.body.text = row.body
            binding.body.visibility = if (row.body.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
            binding.priority.visibility = if (row.highPriority) android.view.View.VISIBLE else android.view.View.GONE
            binding.time.text = row.timeText
            binding.status.text = row.statusText
            binding.reminderName.text = row.reminderName
            binding.reminderName.visibility =
                if (row.reminderName.isBlank()) android.view.View.GONE else android.view.View.VISIBLE

            val color = when {
                row.late -> binding.root.context.getColor(dev.watchhub.app.R.color.health_fail)
                row.status == TaskStatus.OPEN -> com.google.android.material.color.MaterialColors.getColor(
                    binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant,
                )
                else -> binding.root.context.getColor(dev.watchhub.app.R.color.health_info)
            }
            binding.status.setTextColor(color)

            val open = row.status == TaskStatus.OPEN
            binding.actions.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnDone.setOnClickListener { onDone(row) }
            binding.btnSkip.setOnClickListener { onSkip(row) }
            binding.btnSnooze.setOnClickListener { onSnooze(row) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean = when {
            oldItem is Row.Section && newItem is Row.Section -> oldItem.title == newItem.title
            oldItem is Row.Task && newItem is Row.Task -> oldItem.row.id == newItem.row.id
            else -> false
        }

        override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean = oldItem == newItem
    }

    companion object {
        private const val TYPE_SECTION = 0
        private const val TYPE_TASK = 1
    }
}
