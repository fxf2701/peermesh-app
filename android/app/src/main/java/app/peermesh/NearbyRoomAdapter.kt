package app.peermesh

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.peermesh.data.db.entity.NearbyRoomEntity
import app.peermesh.nearby.NearbyRoomExpiryManager

class NearbyRoomAdapter(
    private val onOpen: (NearbyRoomEntity) -> Unit,
) : RecyclerView.Adapter<NearbyRoomAdapter.ViewHolder>() {
    private val items = mutableListOf<NearbyRoomEntity>()
    private val expiryManager = NearbyRoomExpiryManager()

    fun submitList(rooms: List<NearbyRoomEntity>) {
        items.clear()
        items.addAll(rooms)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_nearby_room, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], onOpen, expiryManager)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.nearby_room_title)
        private val participants = view.findViewById<TextView>(R.id.nearby_room_participants)
        private val expiry = view.findViewById<TextView>(R.id.nearby_room_expiry)
        private val open = view.findViewById<Button>(R.id.nearby_room_open)

        fun bind(
            room: NearbyRoomEntity,
            onOpen: (NearbyRoomEntity) -> Unit,
            expiryManager: NearbyRoomExpiryManager,
        ) {
            val context = itemView.context
            title.text = room.name
            val participantCount = room.participant_count ?: 0
            participants.text = context.resources.getQuantityString(
                R.plurals.nearby_room_card_participants,
                participantCount,
                participantCount,
            )
            expiry.text = when (room.state) {
                NearbyRoomEntity.STATE_CLOSED -> context.getString(R.string.nearby_room_closed)
                NearbyRoomEntity.STATE_EXPIRED -> context.getString(R.string.nearby_room_expired)
                else -> context.getString(
                    R.string.nearby_room_expires_in,
                    expiryManager.formatRemaining(room),
                )
            }
            val openEnabled = room.state == NearbyRoomEntity.STATE_ACTIVE
            open.isEnabled = openEnabled
            open.setOnClickListener { if (openEnabled) onOpen(room) }
            itemView.setOnClickListener { if (openEnabled) onOpen(room) }
        }
    }
}
