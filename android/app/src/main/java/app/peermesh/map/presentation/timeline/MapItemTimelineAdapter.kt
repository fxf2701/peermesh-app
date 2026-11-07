package app.peermesh.map.presentation.timeline

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.peermesh.R
import app.peermesh.map.application.timeline.MapItemActivityEntry

/**
 * M3B3 Android list boundary.
 *
 * The adapter preserves controller order and performs no sorting, merging, deduplication or cursor
 * derivation. Rich row formatting remains an Android presentation concern supplied by the Activity.
 */
class MapItemTimelineAdapter(
    private val entryText: (MapItemActivityEntry) -> CharSequence,
) : ListAdapter<MapItemActivityEntry, MapItemTimelineAdapter.EntryViewHolder>(EntryDiff) {

    fun submitEntries(entries: List<MapItemActivityEntry>) {
        submitList(entries.toList())
    }

    /** Rebinds rows after presentation-only enrichment such as owner names changes. */
    fun refreshEntryTexts() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_map_item_timeline_entry, parent, false) as TextView
        return EntryViewHolder(view)
    }

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
        holder.bind(entryText(getItem(position)))
    }

    class EntryViewHolder(
        private val textView: TextView,
    ) : RecyclerView.ViewHolder(textView) {
        fun bind(text: CharSequence) {
            textView.text = text
        }
    }

    private object EntryDiff : DiffUtil.ItemCallback<MapItemActivityEntry>() {
        override fun areItemsTheSame(
            oldItem: MapItemActivityEntry,
            newItem: MapItemActivityEntry,
        ): Boolean = oldItem.entryId == newItem.entryId

        override fun areContentsTheSame(
            oldItem: MapItemActivityEntry,
            newItem: MapItemActivityEntry,
        ): Boolean = oldItem == newItem
    }
}
