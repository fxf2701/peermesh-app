package app.peermesh

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import app.peermesh.data.db.entity.GroupEntity

class GroupAdapter(
    private val onClick: (GroupEntity) -> Unit,
) : RecyclerView.Adapter<GroupAdapter.GroupViewHolder>() {
    private val items = mutableListOf<GroupEntity>()
    private var activeGid: String? = null

    fun submitList(groups: List<GroupEntity>, activeGid: String?) {
        items.clear()
        items.addAll(groups)
        this.activeGid = activeGid
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_group, parent, false)
        return GroupViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(items[position], onClick, activeGid)
    }

    class GroupViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.group_title)
        private val meta = view.findViewById<TextView>(R.id.group_meta)
        private val activeDot = view.findViewById<View>(R.id.group_active_dot)

        fun bind(group: GroupEntity, onClick: (GroupEntity) -> Unit, activeGid: String?) {
            val displayTitle = group.name?.takeIf { it.isNotBlank() }
                ?: itemView.context.getString(
                    R.string.group_fallback_title,
                    group.gid.take(8)
                )
            title.text = displayTitle
            val gidPreview = group.gid.take(12)
            val baseMeta = itemView.context.getString(R.string.group_meta_format, gidPreview)
            val isActive = activeGid != null && group.gid == activeGid
            meta.text = if (isActive) {
                itemView.context.getString(R.string.group_meta_active_format, baseMeta)
            } else {
                baseMeta
            }
            activeDot.visibility = if (isActive) View.VISIBLE else View.GONE
            val surface = resolveThemeColor(com.google.android.material.R.attr.colorSurface, itemView)
            val accent = resolveThemeColor(com.google.android.material.R.attr.colorSecondary, itemView)
            val border = resolveThemeColor(R.attr.peerMeshMenuDivider, itemView)
            val highlight = ColorUtils.blendARGB(surface, accent, 0.05f)
            itemView.background = GradientDrawable().apply {
                cornerRadius = 12f * itemView.resources.displayMetrics.density
                setColor(if (isActive) highlight else surface)
                setStroke(
                    (1f * itemView.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                    if (isActive) accent else border
                )
            }
            meta.setTextColor(
                if (isActive) accent
                else resolveThemeColor(android.R.attr.textColorSecondary, itemView)
            )
            itemView.setOnClickListener { onClick(group) }
        }

        private fun resolveThemeColor(attr: Int, view: View): Int {
            val typedArray = view.context.obtainStyledAttributes(intArrayOf(attr))
            val color = typedArray.getColor(0, 0)
            typedArray.recycle()
            return color
        }
    }
}
