package app.peermesh

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Date

class TrailAdapter(
    private val onDelete: (TrailRecord) -> Unit,
    private val onExport: (TrailRecord) -> Unit,
    private val onView: (TrailRecord) -> Unit,
    private val onStopActive: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val items = mutableListOf<TrailListItem>()

    fun submit(list: List<TrailRecord>, active: TrailActiveState?, isSaving: Boolean) {
        items.clear()
        if (active != null) {
            items.add(TrailListItem.Active(active))
        }
        list.forEach { items.add(TrailListItem.Saved(it, isSaving)) }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is TrailListItem.Active -> VIEW_ACTIVE
        is TrailListItem.Saved -> VIEW_SAVED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_ACTIVE -> ActiveHolder(inflater.inflate(R.layout.item_trail_active, parent, false))
            VIEW_SAVED -> SavedHolder(inflater.inflate(R.layout.item_trail, parent, false))
            else -> throw IllegalArgumentException("Unknown view type")
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is TrailListItem.Active -> (holder as ActiveHolder).bind(item.state)
            is TrailListItem.Saved -> (holder as SavedHolder).bind(item.record, item.isSaving)
        }
    }

    inner class ActiveHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val meta = view.findViewById<TextView>(R.id.trail_active_meta)
        private val stop = view.findViewById<Button>(R.id.trail_active_stop)

        fun bind(state: TrailActiveState) {
            meta.text = itemView.context.getString(
                R.string.trail_active_meta_format,
                state.elapsedLabel,
                formatKm(state.distanceMeters),
                state.pointCount
            )
            stop.setOnClickListener { onStopActive() }
        }
    }

    inner class SavedHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.trail_title)
        private val meta = view.findViewById<TextView>(R.id.trail_meta)
        private val saving = view.findViewById<TextView>(R.id.trail_saving)
        private val viewBtn = view.findViewById<Button>(R.id.trail_view)
        private val delete = view.findViewById<Button>(R.id.trail_delete)
        private val export = view.findViewById<Button>(R.id.trail_export)

        fun bind(record: TrailRecord, isSaving: Boolean) {
            title.text = record.name
            val start = Date(record.start_t * 1000L)
            val end = Date(record.end_t * 1000L)
            val date = DateFormat.getMediumDateFormat(itemView.context).format(start)
            val timeStart = DateFormat.getTimeFormat(itemView.context).format(start)
            val timeEnd = DateFormat.getTimeFormat(itemView.context).format(end)
            val modeLabel = if (PeerMeshCapabilities.canUseAutoTrail && record.mode == TrailRecordingMode.AUTOMATIC) {
                itemView.context.getString(R.string.trail_mode_auto)
            } else {
                itemView.context.getString(R.string.trail_mode_manual)
            }
            meta.text = itemView.context.getString(
                R.string.trail_meta_format,
                modeLabel,
                date,
                timeStart,
                timeEnd,
                formatKm(record.dist_m),
                record.point_count
            )
            saving.visibility = if (isSaving) View.VISIBLE else View.GONE
            export.isEnabled = !isSaving
            viewBtn.isEnabled = !isSaving
            delete.setOnClickListener { onDelete(record) }
            export.setOnClickListener { onExport(record) }
            viewBtn.setOnClickListener { onView(record) }
        }
    }

    private sealed class TrailListItem {
        data class Active(val state: TrailActiveState) : TrailListItem()
        data class Saved(val record: TrailRecord, val isSaving: Boolean) : TrailListItem()
    }

    companion object {
        private const val VIEW_ACTIVE = 1
        private const val VIEW_SAVED = 2
    }

    private fun formatKm(meters: Float): String {
        return String.format(java.util.Locale.US, "%.2f km", meters / 1000f)
    }
}

data class TrailActiveState(
    val elapsedLabel: String,
    val pointCount: Int,
    val distanceMeters: Float,
)
