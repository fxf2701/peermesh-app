package app.peermesh

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.membership.GroupMembershipPolicy
import app.peermesh.moderation.ModerationRepository

class GroupSettingsActivity : AppCompatActivity() {
    private val moderationRepository by lazy { ModerationRepository(applicationContext) }
    private lateinit var removedNotice: TextView
    private lateinit var membersValue: TextView
    private lateinit var blockedValue: TextView
    private lateinit var membersRow: View
    private lateinit var blockedRow: View
    private lateinit var leaveButton: Button
    private lateinit var deleteButton: Button
    private var gid: String = ""
    private var actorDid: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_settings)

        val groupName = intent.getStringExtra(EXTRA_GROUP_NAME)
        gid = intent.getStringExtra(EXTRA_GID).orEmpty()
        actorDid = DeviceIdProvider.getActiveActor(this)

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)
        removedNotice = findViewById(R.id.group_settings_removed_notice)
        membersValue = findViewById(R.id.group_settings_members_value)
        blockedValue = findViewById(R.id.group_settings_blocked_value)
        membersRow = findViewById(R.id.group_settings_members_row)
        blockedRow = findViewById(R.id.group_settings_blocked_row)
        leaveButton = findViewById(R.id.group_settings_leave_group)
        deleteButton = findViewById(R.id.group_settings_delete_group)

        headerTitle.text = getString(R.string.group_settings_title)
        headerMenu.setOnClickListener { finish() }

        if (gid.isBlank()) {
            finish()
            return
        }

        membersRow.setOnClickListener {
            startActivity(Intent(this, GroupMembersActivity::class.java).apply {
                putExtra(GroupMembersActivity.EXTRA_GID, gid)
                putExtra(GroupMembersActivity.EXTRA_GROUP_NAME, groupName)
            })
        }
        blockedRow.setOnClickListener {
            startActivity(Intent(this, GroupBlockedPeersActivity::class.java).apply {
                putExtra(GroupBlockedPeersActivity.EXTRA_GID, gid)
                putExtra(GroupBlockedPeersActivity.EXTRA_GROUP_NAME, groupName)
            })
        }

        leaveButton.setOnClickListener { confirmLeaveGroup(gid, actorDid) }
        deleteButton.setOnClickListener { confirmDeleteGroup(gid, actorDid) }
    }

    override fun onResume() {
        super.onResume()
        StatusIndicatorUpdater.start(this)
        loadSettingsSummary()
    }

    override fun onPause() {
        super.onPause()
        StatusIndicatorUpdater.stop(this)
    }

    private fun confirmLeaveGroup(gid: String, actorDid: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.group_leave_title)
            .setMessage(R.string.group_leave_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.group_leave) { _, _ ->
                deleteGroupLocally(gid, actorDid, R.string.group_leave_done)
            }
            .show()
    }

    private fun confirmDeleteGroup(gid: String, actorDid: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.group_delete_title)
            .setMessage(R.string.group_delete_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.group_delete) { _, _ ->
                deleteGroupLocally(gid, actorDid, R.string.group_delete_done)
            }
            .show()
    }

    private fun deleteGroupLocally(gid: String, actorDid: String, successMessageRes: Int) {
        Thread {
            GroupLocalDataManager.deleteGroupFromDevice(applicationContext, gid)
            runOnUiThread {
                ActiveGroupManager.clearActiveGroup(this, actorDid)
                android.widget.Toast.makeText(this, successMessageRes, android.widget.Toast.LENGTH_SHORT).show()
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                finish()
            }
        }.start()
    }

    private fun loadSettingsSummary() {
        if (gid.isBlank()) return
        Thread {
            val db = AppDatabaseProvider.get(applicationContext)
            val group = db.groupDao().findGroup(gid)
            val activeActorIds = GroupActiveMembers.actorIds(
                db = db,
                gid = gid,
                ownerDid = group?.owner_did,
                currentActorDid = actorDid,
            )
            val memberCount = db.userProfileDao().listByGid(gid).count { it.actor_did in activeActorIds }
            val blockedCount = moderationRepository.listBlockedPeers(gid).size
            val isOwner = group?.owner_did == actorDid
            val removed = GroupMembershipPolicy.isPeerRemoved(db, gid, actorDid)
            runOnUiThread {
                removedNotice.visibility = if (removed) View.VISIBLE else View.GONE
                membersRow.visibility = if (removed) View.GONE else View.VISIBLE
                blockedRow.visibility = if (removed) View.GONE else View.VISIBLE
                membersValue.text = resources.getQuantityString(R.plurals.group_settings_members_count, memberCount, memberCount)
                blockedValue.text = resources.getQuantityString(R.plurals.group_settings_blocked_count, blockedCount, blockedCount)
                leaveButton.visibility = if (isOwner) View.GONE else View.VISIBLE
                deleteButton.visibility = if (isOwner) View.VISIBLE else View.GONE
            }
        }.start()
    }

    companion object {
        const val EXTRA_GID = "extra_gid"
        const val EXTRA_GROUP_NAME = "extra_group_name"
    }
}
