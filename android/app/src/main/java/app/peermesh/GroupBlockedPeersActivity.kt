package app.peermesh

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.UserProfileEntity
import app.peermesh.data.membership.GroupMembershipPolicy
import app.peermesh.moderation.ModerationRepository

class GroupBlockedPeersActivity : AppCompatActivity() {
    private val moderationRepository by lazy { ModerationRepository(applicationContext) }
    private lateinit var removedNotice: TextView
    private lateinit var emptyView: TextView
    private lateinit var recycler: RecyclerView
    private var gid: String = ""
    private var actorDid: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_blocked_peers)

        gid = intent.getStringExtra(EXTRA_GID).orEmpty()
        val groupName = intent.getStringExtra(EXTRA_GROUP_NAME)
        actorDid = DeviceIdProvider.getActiveActor(this)

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)
        val sectionTitle = findViewById<TextView>(R.id.group_blocked_title)
        removedNotice = findViewById(R.id.group_blocked_removed_notice)
        emptyView = findViewById(R.id.group_blocked_empty)
        recycler = findViewById(R.id.group_blocked_list)

        headerTitle.text = getString(R.string.group_settings_blocked_label)
        headerMenu.setOnClickListener { finish() }
        sectionTitle.text = if (groupName.isNullOrBlank()) {
            getString(R.string.group_blocked_title)
        } else {
            getString(R.string.group_blocked_title_format, groupName)
        }
        recycler.layoutManager = LinearLayoutManager(this)

        if (gid.isBlank()) {
            finish()
            return
        }

        loadBlockedPeers()
    }

    override fun onResume() {
        super.onResume()
        StatusIndicatorUpdater.start(this)
        loadBlockedPeers()
    }

    override fun onPause() {
        super.onPause()
        StatusIndicatorUpdater.stop(this)
    }

    private fun loadBlockedPeers() {
        Thread {
            val db = AppDatabaseProvider.get(applicationContext)
            val removed = GroupMembershipPolicy.isPeerRemoved(db, gid, actorDid)
            if (removed) {
                runOnUiThread {
                    removedNotice.visibility = View.VISIBLE
                    emptyView.visibility = View.GONE
                    recycler.visibility = View.GONE
                }
                return@Thread
            }
            val profiles = db.userProfileDao().listByGid(gid).associateBy { it.actor_did }
            val blockedPeers = moderationRepository.listBlockedPeers(gid)
            runOnUiThread {
                removedNotice.visibility = View.GONE
                if (blockedPeers.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    recycler.visibility = View.GONE
                } else {
                    emptyView.visibility = View.GONE
                    recycler.visibility = View.VISIBLE
                    recycler.adapter = BlockedPeersAdapter(blockedPeers, profiles) { blockedPeer ->
                        confirmUnblock(gid, blockedPeer.peerId)
                    }
                }
            }
        }.start()
    }

    private fun confirmUnblock(gid: String, peerId: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.blocked_peers_unblock)
            .setMessage(getString(R.string.blocked_peers_unblock_message, shortPeerId(peerId)))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.blocked_peers_unblock) { _, _ ->
                Thread {
                    moderationRepository.unblockPeer(gid, peerId)
                    runOnUiThread {
                        android.widget.Toast.makeText(this, R.string.blocked_peers_unblocked, android.widget.Toast.LENGTH_SHORT).show()
                        loadBlockedPeers()
                    }
                }.start()
            }
            .show()
    }

    private fun shortPeerId(value: String): String = if (value.length <= 12) value else value.take(12)

    companion object {
        const val EXTRA_GID = "extra_gid"
        const val EXTRA_GROUP_NAME = "extra_group_name"
    }
}

private class BlockedPeersAdapter(
    private val blockedPeers: List<ModerationRepository.BlockedPeerSummary>,
    private val profilesById: Map<String, UserProfileEntity>,
    private val onUnblockRequested: (ModerationRepository.BlockedPeerSummary) -> Unit,
) : RecyclerView.Adapter<BlockedPeersAdapter.BlockedPeerViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BlockedPeerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_blocked_peer, parent, false)
        return BlockedPeerViewHolder(view)
    }

    override fun getItemCount(): Int = blockedPeers.size

    override fun onBindViewHolder(holder: BlockedPeerViewHolder, position: Int) {
        holder.bind(blockedPeers[position], profilesById, onUnblockRequested)
    }

    class BlockedPeerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name = view.findViewById<TextView>(R.id.blocked_peer_name)
        private val meta = view.findViewById<TextView>(R.id.blocked_peer_meta)
        private val unblock = view.findViewById<Button>(R.id.blocked_peer_unblock)

        fun bind(
            blockedPeer: ModerationRepository.BlockedPeerSummary,
            profilesById: Map<String, UserProfileEntity>,
            onUnblockRequested: (ModerationRepository.BlockedPeerSummary) -> Unit,
        ) {
            val profile = profilesById[blockedPeer.peerId]
            name.text = profile?.display_name?.ifBlank { shortPeerId(blockedPeer.peerId) } ?: shortPeerId(blockedPeer.peerId)
            val parts = mutableListOf<String>()
            if (blockedPeer.hideMessages) parts += itemView.context.getString(R.string.blocked_peers_scope_messages)
            if (blockedPeer.hideLocation) parts += itemView.context.getString(R.string.blocked_peers_scope_location)
            if (blockedPeer.hideTrail) parts += itemView.context.getString(R.string.blocked_peers_scope_trail)
            val hidden = if (parts.isEmpty()) itemView.context.getString(R.string.blocked_peers_scope_unknown) else parts.joinToString(", ")
            meta.text = itemView.context.getString(R.string.blocked_peers_item_meta, shortPeerId(blockedPeer.peerId), hidden)
            unblock.setOnClickListener { onUnblockRequested(blockedPeer) }
        }

        private fun shortPeerId(value: String): String = if (value.length <= 12) value else value.take(12)
    }
}
