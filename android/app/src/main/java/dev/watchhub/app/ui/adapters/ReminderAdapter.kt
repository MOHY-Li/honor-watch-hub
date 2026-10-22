package dev.watchhub.app.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.watchhub.app.databinding.ItemReminderBinding
import dev.watchhub.app.databinding.ItemReminderEmptyBinding
import dev.watchhub.app.databinding.ItemReminderHeaderBinding
import dev.watchhub.app.ui.HubUi

/** 提醒管理列表：固定标题 + 提醒行 + 空状态。 */
class ReminderAdapter(
    private val onEnabled: (HubUi.ReminderRow, Boolean) -> Unit,
    private val onEdit: (HubUi.ReminderRow) -> Unit,
    private val onDelete: (HubUi.ReminderRow) -> Unit,
) : ListAdapter<ReminderAdapter.Row, RecyclerView.ViewHolder>(Diff) {

    sealed class Row {
        data object Header : Row()
        data object Empty : Row()
        data class Item(val row: HubUi.ReminderRow) : Row()
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        Row.Header -> TYPE_HEADER
        Row.Empty -> TYPE_EMPTY
        is Row.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(ItemReminderHeaderBinding.inflate(inflater, parent, false))
            TYPE_EMPTY -> EmptyVH(ItemReminderEmptyBinding.inflate(inflater, parent, false))
            else -> ItemVH(ItemReminderBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            Row.Header, Row.Empty -> Unit
            is Row.Item -> (holder as ItemVH).bind(row.row)
        }
    }

    private class HeaderVH(binding: ItemReminderHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    private class EmptyVH(binding: ItemReminderEmptyBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class ItemVH(private val binding: ItemReminderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HubUi.ReminderRow) {
            binding.name.text = row.name
            binding.title.text = row.title
            binding.schedule.text = row.scheduleText
            val meta = StringBuilder(row.priorityText).apply {
                if (row.calendarSync) append(" · 日历同步")
            }
            binding.meta.text = meta.toString()

            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = row.enabled
            binding.switchEnabled.setOnCheckedChangeListener { _, checked -> onEnabled(row, checked) }

            binding.btnEdit.setOnClickListener { onEdit(row) }
            binding.btnDelete.setOnClickListener { onDelete(row) }

            binding.name.alpha = if (row.enabled) 1f else 0.5f
            binding.title.alpha = if (row.enabled) 1f else 0.5f
            binding.schedule.alpha = if (row.enabled) 1f else 0.5f
            binding.meta.visibility = View.VISIBLE
        }
    }

    private object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean = when {
            oldItem is Row.Item && newItem is Row.Item -> oldItem.row.id == newItem.row.id
            else -> oldItem::class == newItem::class
        }

        override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean = oldItem == newItem
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_EMPTY = 1
        private const val TYPE_ITEM = 2
    }
}
