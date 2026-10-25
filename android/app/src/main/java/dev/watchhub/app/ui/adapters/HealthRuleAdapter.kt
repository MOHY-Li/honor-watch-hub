package dev.watchhub.app.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.watchhub.app.databinding.ItemHealthRuleBinding
import dev.watchhub.app.databinding.ItemHealthRuleEmptyBinding
import dev.watchhub.app.databinding.ItemHealthRuleHeaderBinding
import dev.watchhub.app.databinding.ItemHealthRuleNewBinding
import dev.watchhub.app.ui.HubUi

/** 任务页「健康规则」区块：固定标题 + 规则行 + 新建入口 + 空态。 */
class HealthRuleAdapter(
    private val onEnabled: (HubUi.HealthRuleRow, Boolean) -> Unit,
    private val onEdit: (HubUi.HealthRuleRow) -> Unit,
    private val onDelete: (HubUi.HealthRuleRow) -> Unit,
    private val onNew: () -> Unit,
) : ListAdapter<HealthRuleAdapter.Row, RecyclerView.ViewHolder>(Diff) {

    sealed class Row {
        data object Header : Row()
        data object Empty : Row()
        data object NewEntry : Row()
        data class Item(val row: HubUi.HealthRuleRow) : Row()
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        Row.Header -> TYPE_HEADER
        Row.Empty -> TYPE_EMPTY
        Row.NewEntry -> TYPE_NEW
        is Row.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(ItemHealthRuleHeaderBinding.inflate(inflater, parent, false))
            TYPE_EMPTY -> EmptyVH(ItemHealthRuleEmptyBinding.inflate(inflater, parent, false))
            TYPE_NEW -> NewVH(ItemHealthRuleNewBinding.inflate(inflater, parent, false))
            else -> ItemVH(ItemHealthRuleBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            Row.Header, Row.Empty, Row.NewEntry -> Unit
            is Row.Item -> (holder as ItemVH).bind(row.row)
        }
    }

    private class HeaderVH(binding: ItemHealthRuleHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    private class EmptyVH(binding: ItemHealthRuleEmptyBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class NewVH(binding: ItemHealthRuleNewBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener { onNew() }
        }
    }

    private inner class ItemVH(private val binding: ItemHealthRuleBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HubUi.HealthRuleRow) {
            binding.name.text = row.name
            binding.summary.text = row.summaryText
            binding.meta.text = row.priorityText

            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = row.enabled
            binding.switchEnabled.setOnCheckedChangeListener { _, checked -> onEnabled(row, checked) }

            binding.btnEdit.setOnClickListener { onEdit(row) }
            binding.btnDelete.setOnClickListener { onDelete(row) }

            val alpha = if (row.enabled) 1f else 0.5f
            binding.name.alpha = alpha
            binding.summary.alpha = alpha
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
        private const val TYPE_NEW = 3
    }
}
