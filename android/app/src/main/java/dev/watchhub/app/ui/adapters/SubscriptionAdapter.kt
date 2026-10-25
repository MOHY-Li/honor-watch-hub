package dev.watchhub.app.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import dev.watchhub.app.R
import dev.watchhub.app.databinding.ItemSubscriptionBinding
import dev.watchhub.app.ui.HubUi

/**
 * 订阅源横向列表：点卡片按该源筛选（再点一次回全部），卡片上的按钮管编辑/暂停/取消。
 * 筛选没有单独的控件行——同一份订阅列表既是管理入口也是筛选入口。
 */
class SubscriptionAdapter(
    private val onFilter: (HubUi.SubscriptionRow) -> Unit,
    private val onToggle: (HubUi.SubscriptionRow, Boolean) -> Unit,
    private val onEdit: (HubUi.SubscriptionRow) -> Unit,
    private val onDelete: (HubUi.SubscriptionRow) -> Unit,
) : ListAdapter<HubUi.SubscriptionRow, SubscriptionAdapter.VH>(Diff) {

    /** 当前筛选选中的源名；null = 全部。变更后调 [setSelection] 触发重绑。 */
    private var selectedName: String? = null

    fun setSelection(name: String?) {
        selectedName = name
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSubscriptionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val binding: ItemSubscriptionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HubUi.SubscriptionRow) {
            val ctx = binding.root.context
            binding.name.text = row.name
            binding.unread.text = ctx.getString(R.string.subs_unread, row.unread)
            binding.unread.visibility = if (row.unread > 0) View.VISIBLE else View.GONE
            binding.status.text = row.statusText

            binding.btnToggle.text = ctx.getString(
                if (row.enabled) R.string.subs_pause else R.string.subs_resume,
            )
            binding.btnToggle.setOnClickListener { onToggle(row, !row.enabled) }
            binding.btnEdit.setOnClickListener { onEdit(row) }
            binding.btnDelete.setOnClickListener { onDelete(row) }

            binding.root.setOnClickListener { onFilter(row) }
            if (row.name == selectedName) {
                binding.root.strokeColor = MaterialColors.getColor(
                    binding.root, com.google.android.material.R.attr.colorPrimary,
                )
                binding.root.strokeWidth = (2 * ctx.resources.displayMetrics.density).toInt()
            } else {
                binding.root.strokeWidth = 0
            }

            binding.name.alpha = if (row.enabled) 1f else 0.5f
            binding.status.alpha = if (row.enabled) 1f else 0.6f
        }
    }

    private object Diff : DiffUtil.ItemCallback<HubUi.SubscriptionRow>() {
        override fun areItemsTheSame(oldItem: HubUi.SubscriptionRow, newItem: HubUi.SubscriptionRow): Boolean =
            oldItem.name == newItem.name

        override fun areContentsTheSame(oldItem: HubUi.SubscriptionRow, newItem: HubUi.SubscriptionRow): Boolean =
            oldItem == newItem
    }
}
