package dev.watchhub.app.ui.adapters

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.watchhub.app.R
import dev.watchhub.app.databinding.ItemHealthBinding
import dev.watchhub.app.health.HealthItem
import dev.watchhub.app.health.HealthLevel

/** 体检列表：按 OK/WARN/FAIL/INFO 上色，有 action 的项可点。 */
class HealthAdapter(
    private val onClick: (HealthItem) -> Unit,
) : ListAdapter<HealthItem, HealthAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemHealthBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val binding: ItemHealthBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HealthItem) {
            val ctx = binding.root.context
            val color = ctx.getColor(
                when (item.level) {
                    HealthLevel.OK -> R.color.health_ok
                    HealthLevel.WARN -> R.color.health_warn
                    HealthLevel.FAIL -> R.color.health_fail
                    HealthLevel.INFO -> R.color.health_info
                }
            )
            binding.title.text = item.title
            binding.detail.text = item.detail
            binding.dot.backgroundTintList = ColorStateList.valueOf(color)
            binding.detail.setTextColor(
                if (item.level == HealthLevel.FAIL || item.level == HealthLevel.WARN) color
                else com.google.android.material.color.MaterialColors.getColor(
                    binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant,
                )
            )

            val actionable = item.action != null
            binding.actionHint.visibility = if (actionable) View.VISIBLE else View.GONE
            binding.root.isClickable = actionable
            binding.root.isFocusable = actionable
            binding.root.setOnClickListener { if (actionable) onClick(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<HealthItem>() {
        override fun areItemsTheSame(oldItem: HealthItem, newItem: HealthItem): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: HealthItem, newItem: HealthItem): Boolean = oldItem == newItem
    }
}
