package app.peermesh

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.peermesh.data.db.entity.LiveTrailEntity
import java.util.Date
import kotlin.math.absoluteValue

class SharedTrailsAdapter(
    private val currentActorDid: String?,
    private val onShowOnMap: (LiveTrailEntity) -> Unit,
    private val onReplayTrail: (LiveTrailEntity) -> Unit,
    private val onDeleteArchived: (LiveTrailEntity) -> Unit,
) : RecyclerView.Adapter<SharedTrailsAdapter.TrailHolder>() {

    private val trails = mutableListOf<LiveTrailEntity>()
    private var ownerNames: Map<String, String> = emptyMap()
    private var staleIds: Set<String> = emptySet()

    fun submit(
        nextTrails: List<LiveTrailEntity>,
        nextOwnerNames: Map<String, String>,
        nextStaleIds: Set<String>,
    ) {
        trails.clear()
        trails.addAll(nextTrails)

        ownerNames = nextOwnerNames
        staleIds = nextStaleIds

        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrailHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_map_trail, parent, false)

        return TrailHolder(view)
    }

    override fun onBindViewHolder(holder: TrailHolder, position: Int) {
        holder.bind(trails[position])
    }

    override fun getItemCount(): Int = trails.size

    inner class TrailHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.trail_label)
        private val meta = view.findViewById<TextView>(R.id.trail_meta)
        private val toggle = view.findViewById<Switch>(R.id.trail_toggle)
        private val deleteArchived = view.findViewById<Button>(R.id.trail_delete_archived)
        private val showOnMap = view.findViewById<Button>(R.id.trail_show_on_map)
        private val replayButton = view.findViewById<Button>(R.id.trail_replay)

        fun bind(trail: LiveTrailEntity) {
            val context = itemView.context

            val ownerName = ownerNames[trail.owner_did]
                ?: shortAuthor(trail.owner_did)

            val status = when {
                trail.active != 1 ->
                    context.getString(R.string.shared_trails_status_archived)

                staleIds.contains(trail.trail_id) ->
                    context.getString(R.string.shared_trails_status_stale)

                else ->
                    context.getString(R.string.shared_trails_status_live)
            }

            val start = Date(trail.start_t * 1000L)
            val endSeconds = trail.end_t ?: System.currentTimeMillis() / 1000L
            val end = Date(endSeconds * 1000L)

            val date = DateFormat.getMediumDateFormat(context).format(start)
            val timeStart = DateFormat.getTimeFormat(context).format(start)
            val timeEnd = DateFormat.getTimeFormat(context).format(end)

            title.text = context.getString(R.string.map_live_trail_title, ownerName)
            meta.text = context.getString(
                R.string.map_live_trail_meta_format,
                status,
                date,
                timeStart,
                timeEnd,
                formatKm(trail.dist_m),
                trail.point_count
            )

            toggle.visibility = View.GONE

            val isReceiver = trail.owner_did != currentActorDid
            if (trail.active != 1 && isReceiver) {
                deleteArchived.visibility = View.VISIBLE
                deleteArchived.text = context.getString(R.string.map_live_trail_delete)
                deleteArchived.setOnClickListener {
                    onDeleteArchived(trail)
                }
            } else {
                deleteArchived.visibility = View.GONE
                deleteArchived.setOnClickListener(null)
            }

            showOnMap.setOnClickListener {
                onShowOnMap(trail)
            }

            replayButton.setOnClickListener {
                onReplayTrail(trail)
            }
        }
    }

    private fun shortAuthor(author: String): String {
        return if (author.length <= 6) {
            author
        } else {
            "${author.take(4)}…${author.takeLast(2)}"
        }
    }

    private fun formatKm(meters: Float): String {
        return "${String.format("%.2f", meters.absoluteValue / 1000f)} km"
    }
}