package app.peermesh

import android.os.Bundle
import android.view.View
import android.widget.Toast
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.LiveTrailEntity

class SharedTrailsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SharedTrailsAdapter
    private lateinit var emptyText: TextView
    private lateinit var filterAllButton: Button
    private lateinit var filterLiveButton: Button
    private lateinit var filterArchivedButton: Button

    private var allTrails: List<LiveTrailEntity> = emptyList()
    private var ownerNames: Map<String, String> = emptyMap()
    private var staleIds: Set<String> = emptySet()
    private var currentFilter: TrailFilter = TrailFilter.ALL
    private enum class TrailFilter {
        ALL,
        LIVE,
        ARCHIVED
    }

    private var gid: String = ""
    private var currentActorDid: String? = null
    private var hasLoadedOnce = false
    private var isLoadingTrails = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_shared_trails)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.shared_trails)

        gid = intent.getStringExtra(GroupMapActivity.EXTRA_GID).orEmpty()
        currentActorDid = DeviceIdProvider.get(this)

        recyclerView = findViewById(R.id.sharedTrailsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = SharedTrailsAdapter(
            currentActorDid = currentActorDid,
            onShowOnMap = { trail ->
                startActivity(
                    android.content.Intent(this, GroupMapActivity::class.java)
                        .putExtra(GroupMapActivity.EXTRA_GID, gid)
                        .putExtra(GroupMapActivity.EXTRA_TRAIL_ID, trail.trail_id)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
            },
            onDeleteArchived = { trail ->
                confirmDeleteArchivedTrail(trail)
            },
            onReplayTrail = { trail ->
                startActivity(
                    android.content.Intent(
                        this,
                        TrailReplayActivity::class.java
                    ).putExtra(
                        TrailReplayActivity.EXTRA_TRAIL_ID,
                        trail.trail_id
                    )
                )
            },
        )

        recyclerView.adapter = adapter

        emptyText = findViewById(R.id.sharedTrailsEmptyText)
        filterAllButton = findViewById(R.id.filterAllButton)
        filterLiveButton = findViewById(R.id.filterLiveButton)
        filterArchivedButton = findViewById(R.id.filterArchivedButton)

        filterAllButton.setOnClickListener {
            currentFilter = TrailFilter.ALL
            renderTrails()
        }

        filterLiveButton.setOnClickListener {
            currentFilter = TrailFilter.LIVE
            renderTrails()
        }

        filterArchivedButton.setOnClickListener {
            currentFilter = TrailFilter.ARCHIVED
            renderTrails()
        }

        if (gid.isBlank()) {
            Toast.makeText(this, "Missing group id", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadTrails()
    }

    private fun loadTrails() {
        if (isLoadingTrails) return
        isLoadingTrails = true

        Thread {
            val db = AppDatabaseProvider.get(this)
            val nowSeconds = System.currentTimeMillis() / 1000L

            val trails = db.liveTrailDao().listTrails(gid, MAX_ITEMS)

            val profiles = db.userProfileDao().listByGid(gid)
            val ownerNames = profiles.associate { profile ->
                profile.actor_did to profile.display_name
            }

            val staleIds = trails
                .filter { trail ->
                    trail.active == 1 && nowSeconds - trail.updated_t >= LIVE_TRAIL_STALE_SEC
                }
                .map { trail -> trail.trail_id }
                .toSet()

            runOnUiThread {
                this.allTrails = trails
                this.ownerNames = ownerNames
                this.staleIds = staleIds

                renderTrails()
                hasLoadedOnce = true
                isLoadingTrails = false
            }
        }.start()
    }

    private fun confirmDeleteArchivedTrail(trail: LiveTrailEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.map_live_trail_delete)
            .setMessage("Delete this archived shared trail?")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.map_live_trail_delete) { _, _ ->
                deleteArchivedTrail(trail)
            }
            .show()
    }

    private fun deleteArchivedTrail(trail: LiveTrailEntity) {
        Thread {
            val db = AppDatabaseProvider.get(this)

            db.liveTrailDao().deletePointsByTrailIds(listOf(trail.trail_id))
            db.liveTrailDao().deleteTrailsByIds(listOf(trail.trail_id))

            runOnUiThread {
                Toast.makeText(this, "Shared trail deleted", Toast.LENGTH_SHORT).show()
                loadTrails()
            }
        }.start()
    }

    private fun renderTrails() {
        val filtered = when (currentFilter) {
            TrailFilter.ALL -> allTrails
            TrailFilter.LIVE -> allTrails.filter { it.active == 1 }
            TrailFilter.ARCHIVED -> allTrails.filter { it.active != 1 }
        }.sortedWith(
            compareByDescending<LiveTrailEntity> { it.active == 1 }
                .thenByDescending { it.updated_t }
        )

        adapter.submit(
            nextTrails = filtered,
            nextOwnerNames = ownerNames,
            nextStaleIds = staleIds,
        )

        val emptyMessage = when (currentFilter) {
            TrailFilter.ALL -> R.string.shared_trails_empty_all
            TrailFilter.LIVE -> R.string.shared_trails_empty_live
            TrailFilter.ARCHIVED -> R.string.shared_trails_empty_archived
        }

        emptyText.setText(emptyMessage)
        emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE

        filterAllButton.isSelected = currentFilter == TrailFilter.ALL
        filterLiveButton.isSelected = currentFilter == TrailFilter.LIVE
        filterArchivedButton.isSelected = currentFilter == TrailFilter.ARCHIVED
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        if (hasLoadedOnce && !isLoadingTrails) {
            loadTrails()
        }
    }

    companion object {
        private const val MAX_ITEMS = 200
        private const val LIVE_TRAIL_STALE_SEC = 120L
    }
}