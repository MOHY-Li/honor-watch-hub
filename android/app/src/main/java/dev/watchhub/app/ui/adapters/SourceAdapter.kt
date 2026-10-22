package dev.watchhub.app.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.watchhub.app.R
import dev.watchhub.app.databinding.ItemSourceBinding
import dev.watchhub.app.health.HealthLevel
import dev.watchhub.app.ui.HubUi

/** 数据源列表：启停、立即检查、编辑、删除。 */
class SourceAdapter(
    private val onEnabled: (HubUi.SourceRow, Boolean) -> Unit,
    private val onCheck: (HubUi.SourceRow) -> Unit,
    private val onEdit: (HubUi.SourceRow) -> Unit,
    private val onDelete: (HubUi.SourceRow) -> Unit,
) : ListAdapter<HubUi.SourceRow, SourceAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val binding: ItemSourceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HubUi.SourceRow) {
            binding.name.text = row.name
            binding.kind.text = "类型：${row.kind} · ${row.intervalText}"
            binding.status.text = row.statusText
            binding.status.setTextColor(
                binding.root.context.getColor(
                    when (row.level) {
                        HealthLevel.OK -> R.color.health_ok
                        HealthLevel.WARN -> R.color.health_warn
                        HealthLevel.FAIL -> R.color.health_fail
                        HealthLevel.INFO -> R.color.health_info
                    }
                )
            )

            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = row.enabled
            binding.switchEnabled.setOnCheckedChangeListener { _, checked -> onEnabled(row, checked) }

            binding.btnCheck.isEnabled = row.enabled
            binding.btnCheck.alpha = if (row.enabled) 1f else 0.4f
            binding.btnCheck.setOnClickListener { if (row.enabled) onCheck(row) }
            binding.btnEdit.setOnClickListener { onEdit(row) }
            binding.btnDelete.setOnClickListener { onDelete(row) }

            binding.name.alpha = if (row.enabled) 1f else 0.5f
            binding.kind.alpha = if (row.enabled) 1f else 0.5f
            binding.status.visibility = View.VISIBLE
        }
    }

    private object Diff : DiffUtil.ItemCallback<HubUi.SourceRow>() {
        override fun areItemsTheSame(oldItem: HubUi.SourceRow, newItem: HubUi.SourceRow): Boolean =
            oldItem.name == newItem.name

        override fun areContentsTheSame(oldItem: HubUi.SourceRow, newItem: HubUi.SourceRow): Boolean =
            oldItem == newItem
    }
}
