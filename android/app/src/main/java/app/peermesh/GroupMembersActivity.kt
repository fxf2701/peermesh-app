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
import app.peermesh.data.db.entity.RemovedMemberEntity
import app.peermesh.data.db.entity.UserProfileEntity
import app.peermesh.data.membership.GroupMembershipPolicy

class GroupMembersActivity : AppCompatActivity() {
    private var gid: String = ""
    private var groupName: String? = null
    private var actorDid: String = ""
    private lateinit var removedNotice: TextView
    private lateinit var emptyView: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var sectionTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_members)

        gid = intent.getStringExtra(EXTRA_GID).orEmpty()
        groupName = intent.getStringExtra(EXTRA_GROUP_NAME)
        actorDid = DeviceIdProvider.getActiveActor(this)

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)
        sectionTitle = findViewById(R.id.group_members_title)
        removedNotice = findViewById(R.id.group_members_removed_notice)
        emptyView = findViewById(R.id.group_members_empty)
        recycler = findViewById(R.id.group_members_list)

        headerTitle.text = getString(R.string.group_settings_members_label)
        headerMenu.setOnClickListener { finish() }
        sectionTitle.text = if (groupName.isNullOrBlank()) {
            getString(R.string.group_members_title)
        } else {
            getString(R.string.group_members_title_format, groupName)
        }
        recycler.layoutManager = LinearLayoutManager(this)

        if (gid.isBlank()) {
            finish()
            return
        }
    }

    override fun onResume() {
        super.onResume()
        StatusIndicatorUpdater.start(this)
        loadMembers()
    }

    override fun onPause() {
        super.onPause()
        StatusIndicatorUpdater.stop(this)
    }

    companion object {
        const val EXTRA_GID = "extra_gid"
        const val EXTRA_GROUP_NAME = "extra_group_name"
    }

    private fun loadMembers() {
        Thread {
            val db = AppDatabaseProvider.get(applicationContext)
            val group = db.groupDao().findGroup(gid)
            val removed = GroupMembershipPolicy.isPeerRemoved(db, gid, actorDid)
            if (removed) {
                runOnUiThread {
                    removedNotice.visibility = View.VISIBLE
                    emptyView.visibility = View.GONE
                    recycler.visibility = View.GONE
                }
                return@Thread
            }
            val activeActorIds = GroupActiveMembers.actorIds(
                db = db,
                gid = gid,
                ownerDid = group?.owner_did,
                currentActorDid = actorDid,
            )
            val members = db.userProfileDao().listByGid(gid)
                .filter { it.actor_did in activeActorIds }
                .sortedBy { it.display_name.lowercase() }
            val isOwner = group?.owner_did == actorDid
            val removedByPeerId = db.removedMemberDao().listByGroup(gid).associateBy { it.peerId }
            runOnUiThread {
                removedNotice.visibility = View.GONE
                if (members.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    recycler.visibility = View.GONE
                } else {
                    emptyView.visibility = View.GONE
                    recycler.visibility = View.VISIBLE
                    recycler.adapter = GroupMembersAdapter(
                        members = members,
                        ownerDid = group?.owner_did,
                        currentActorDid = actorDid,
                        isOwner = isOwner,
                        removedByPeerId = removedByPeerId,
                    ) { member ->
                        confirmRemoveMember(member)
                    }
                }
            }
        }.start()
    }

    private fun confirmRemoveMember(member: UserProfileEntity) {
        val title = member.display_name.ifBlank { shortPeerId(member.actor_did) }
        AlertDialog.Builder(this)
            .setTitle(R.string.group_remove_member_title)
            .setMessage(getString(R.string.group_remove_member_message, title))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.group_remove_member) { _, _ ->
                Thread {
                    val ok = SysEventWriter.removeMember(applicationContext, gid, member.actor_did)
                    runOnUiThread {
                        if (ok) {
                            android.widget.Toast.makeText(
                                this,
                                R.string.group_remove_member_done,
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            loadMembers()
                        } else {
                            android.widget.Toast.makeText(
                                this,
                                R.string.group_remove_member_failed,
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }.start()
            }
            .show()
    }

    private fun shortPeerId(value: String): String = if (value.length <= 12) value else value.take(12)
}

private class GroupMembersAdapter(
    private val members: List<UserProfileEntity>,
    private val ownerDid: String?,
    private val currentActorDid: String,
    private val isOwner: Boolean,
    private val removedByPeerId: Map<String, RemovedMemberEntity>,
    private val onRemoveRequested: (UserProfileEntity) -> Unit,
) : RecyclerView.Adapter<GroupMembersAdapter.MemberViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_group_member, parent, false)
        return MemberViewHolder(view)
    }

    override fun getItemCount(): Int = members.size

    override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
        holder.bind(members[position], ownerDid, currentActorDid, isOwner, removedByPeerId, onRemoveRequested)
    }

    class MemberViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name = view.findViewById<TextView>(R.id.member_name)
        private val meta = view.findViewById<TextView>(R.id.member_meta)
        private val remove = view.findViewById<Button>(R.id.member_remove)

        fun bind(
            member: UserProfileEntity,
            ownerDid: String?,
            currentActorDid: String,
            isOwner: Boolean,
            removedByPeerId: Map<String, RemovedMemberEntity>,
            onRemoveRequested: (UserProfileEntity) -> Unit,
        ) {
            val isCurrentUser = member.actor_did == currentActorDid
            val isMemberOwner = member.actor_did == ownerDid
            val isRemoved = removedByPeerId.containsKey(member.actor_did)
            name.text = member.display_name.ifBlank { shortPeerId(member.actor_did) }
            val roles = mutableListOf(shortPeerId(member.actor_did))
            if (isMemberOwner) roles += itemView.context.getString(R.string.group_member_role_owner)
            if (isCurrentUser) roles += itemView.context.getString(R.string.group_member_role_you)
            if (isRemoved) roles += itemView.context.getString(R.string.group_member_role_removed)
            meta.text = roles.joinToString(" · ")
            val canRemove = isOwner && !isCurrentUser && !isMemberOwner && !isRemoved
            remove.visibility = if (isOwner) View.VISIBLE else View.GONE
            remove.isEnabled = canRemove
            remove.alpha = if (canRemove) 1f else 0.4f
            remove.setOnClickListener {
                if (canRemove) onRemoveRequested(member)
            }
        }

        private fun shortPeerId(value: String): String = if (value.length <= 12) value else value.take(12)
    }
}
