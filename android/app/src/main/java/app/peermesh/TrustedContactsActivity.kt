package app.peermesh

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.UserProfileEntity

class TrustedContactsActivity : AppCompatActivity() {
    private var currentGid: String = ""
    private val selectedContacts = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        if (!PeerMeshProGate.require(this, PeerMeshProGate.FEATURE_SHARED_LIVE_TRAIL)) {
            finish()
            return
        }
        setContentView(R.layout.activity_trusted_contacts)

        val gid = intent.getStringExtra(EXTRA_GID)
            ?: ActiveGroupManager.getActiveGroup(this)
            ?: ""
        currentGid = gid

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)
        headerTitle.text = getString(R.string.trusted_contacts_title)
        headerMenu.setOnClickListener { finish() }

        if (currentGid.isBlank()) {
            toast(getString(R.string.trusted_contacts_group_missing))
            finish()
            return
        }

        findViewById<Button>(R.id.trusted_contacts_save).setOnClickListener {
            TrustedContactsStore.setContacts(this, currentGid, selectedContacts.toList())
            updateSummary()
            toast(getString(R.string.trusted_contacts_saved))
        }

        loadMembers()
    }

    override fun onResume() {
        super.onResume()
        StatusIndicatorUpdater.start(this)
    }

    override fun onPause() {
        super.onPause()
        StatusIndicatorUpdater.stop(this)
    }

    private fun loadMembers() {
        val list = findViewById<LinearLayout>(R.id.trusted_contacts_list)
        val empty = findViewById<TextView>(R.id.trusted_contacts_empty)
        Thread {
            val db = AppDatabaseProvider.get(this)
            val actorDid = DeviceIdProvider.get(this)
            val profiles = db.userProfileDao().listByGid(currentGid)
                .filter { it.actor_did != actorDid }
            val current = TrustedContactsStore.getContacts(this, currentGid).toSet()
            runOnUiThread {
                selectedContacts.clear()
                selectedContacts.addAll(current)
                list.removeAllViews()
                empty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
                profiles.forEach { profile ->
                    list.addView(buildContactRow(profile))
                }
                updateSummary()
            }
        }.start()
    }

    private fun buildContactRow(profile: UserProfileEntity): View {
        val label = displayLabel(profile)
        return CheckBox(this).apply {
            text = label
            textSize = 16f
            isChecked = selectedContacts.contains(profile.actor_did)
            setPadding(0, 12.dp(), 0, 12.dp())
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    selectedContacts.add(profile.actor_did)
                } else {
                    selectedContacts.remove(profile.actor_did)
                }
                updateSummary()
            }
        }
    }

    private fun displayLabel(profile: UserProfileEntity): String {
        val name = profile.display_name.ifBlank { shortAuthorId(profile.actor_did) }
        val avatar = profile.avatar_id?.let { AvatarUtils.avatarEmojiForId(this, it) }
            ?: AvatarUtils.avatarInitials(name)
        return "$avatar $name"
    }

    private fun updateSummary() {
        findViewById<TextView>(R.id.trusted_contacts_summary).text = resources.getQuantityString(
            R.plurals.trusted_contacts_selected_count,
            selectedContacts.size,
            selectedContacts.size,
        )
    }

    private fun shortAuthorId(actorDid: String): String {
        return if (actorDid.length <= 6) actorDid else actorDid.takeLast(6)
    }

    private fun Int.dp(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_GID = "extra_gid"
    }
}
