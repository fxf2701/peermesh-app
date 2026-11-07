package app.peermesh

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.peermesh.data.db.entity.LiveTrailEntity
import app.peermesh.data.db.entity.MapAreaEntity
import app.peermesh.data.db.entity.MapItemVoteEntity
import app.peermesh.data.db.entity.MapMarkerEntity
import app.peermesh.data.db.entity.TrailPointEntity
import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupMapActivityM3D2Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun setUp() {
        GroupMapActivityTestSeams.reset()
        GroupMapActivityTestSeams.bypassFeatureGate = true
        GroupMapActivityTestSeams.suppressBackgroundMapLoads = true
    }

    @After fun tearDown() {
        GroupMapActivityTestSeams.reset()
    }

    @Test fun immediateMarkerAndAreaVotesApplyAfterShowAndRestoreLockedSaveState() {
        launchGroup("A").use { scenario ->
            val markerApplied = CountDownLatch(1)
            GroupMapActivityTestSeams.voteLoader = { gid, itemId, actorDid ->
                vote(gid, itemId, "marker", actorDid, "marker vote note", 2)
            }
            GroupMapActivityTestSeams.onVoteApplied = markerApplied::countDown

            scenario.onActivity { openMarkerDialog(it, lockedMarker("A", "marker-1")) }
            assertTrue("marker vote was not applied", markerApplied.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val dialog = currentMapDialog(activity)
                assertTrue(dialog.isShowing)
                assertEquals("marker vote note", dialog.text(R.id.marker_note))
                assertTrue(dialog.radio(R.id.marker_conf_observed).isChecked)
                assertFalse(dialog.radio(R.id.marker_conf_observed).isEnabled)
                assertFalse(dialog.radio(R.id.marker_conf_reported).isEnabled)
                assertEquals(View.VISIBLE, dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
                dialog.dismiss()
            }

            val areaApplied = CountDownLatch(1)
            GroupMapActivityTestSeams.voteLoader = { gid, itemId, actorDid ->
                vote(gid, itemId, "area", actorDid, "area vote note", 1)
            }
            GroupMapActivityTestSeams.onVoteApplied = areaApplied::countDown

            scenario.onActivity { openAreaDialog(it, lockedArea("A", "area-1")) }
            assertTrue("area vote was not applied", areaApplied.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val dialog = currentMapDialog(activity)
                assertTrue(dialog.isShowing)
                assertEquals("area vote note", dialog.text(R.id.area_note))
                assertTrue(dialog.radio(R.id.area_conf_reported).isChecked)
                assertFalse(dialog.radio(R.id.area_conf_observed).isEnabled)
                assertFalse(dialog.radio(R.id.area_conf_reported).isEnabled)
                assertEquals(View.VISIBLE, dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
            }
        }
    }

    @Test fun lockedMarkerAndAreaWithoutPriorVoteKeepSaveHidden() {
        launchGroup("A").use { scenario ->
            GroupMapActivityTestSeams.voteLoader = { _, _, _ -> null }

            val markerApplied = CountDownLatch(1)
            GroupMapActivityTestSeams.onVoteApplied = markerApplied::countDown
            scenario.onActivity { openMarkerDialog(it, lockedMarker("A", "marker-none")) }
            assertTrue("null marker vote was not delivered", markerApplied.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val dialog = currentMapDialog(activity)
                assertEquals(View.GONE, dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
                assertEquals(View.GONE, dialog.findViewById<View>(R.id.marker_note)?.visibility)
                assertEquals(View.GONE, dialog.findViewById<View>(R.id.marker_conf_observed)?.visibility)
                assertEquals(View.GONE, dialog.findViewById<View>(R.id.marker_conf_reported)?.visibility)
                dialog.dismiss()
            }

            val areaApplied = CountDownLatch(1)
            GroupMapActivityTestSeams.onVoteApplied = areaApplied::countDown
            scenario.onActivity { openAreaDialog(it, lockedArea("A", "area-none")) }
            assertTrue("null area vote was not delivered", areaApplied.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val dialog = currentMapDialog(activity)
                assertEquals(View.GONE, dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
                assertEquals(View.GONE, dialog.findViewById<View>(R.id.area_note)?.visibility)
                assertEquals(View.GONE, dialog.findViewById<View>(R.id.area_conf_observed)?.visibility)
                assertEquals(View.GONE, dialog.findViewById<View>(R.id.area_conf_reported)?.visibility)
            }
        }
    }

    @Test fun groupSwitchDismissesDialogAndRejectsHeldAreaVoteCompletion() {
        val queryStarted = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        val resultDelivered = CountDownLatch(1)
        val appliedCount = AtomicInteger()
        GroupMapActivityTestSeams.voteLoader = { gid, itemId, actorDid ->
            queryStarted.countDown()
            releaseQuery.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            vote(gid, itemId, "area", actorDid, "late A vote", 2)
        }
        GroupMapActivityTestSeams.onVoteResultDelivered = resultDelivered::countDown
        GroupMapActivityTestSeams.onVoteApplied = { appliedCount.incrementAndGet() }

        launchGroup("A").use { scenario ->
            val dialogRef = AtomicReference<AlertDialog>()
            scenario.onActivity { activity ->
                openAreaDialog(activity, lockedArea("A", "area-late"))
                dialogRef.set(currentMapDialog(activity))
            }
            assertTrue("held area vote did not start", queryStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            scenario.onActivity { activity ->
                deliverNewIntent(activity, "B")
                assertFalse(dialogRef.get().isShowing)
                assertTrue(openMapDialogs(activity).isEmpty())
            }

            releaseQuery.countDown()
            assertTrue("late area vote was not delivered", resultDelivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertEquals(0, appliedCount.get())
            scenario.onActivity { activity ->
                assertEquals("B", field(activity, "currentGid").get(activity))
                assertTrue(openMapDialogs(activity).isEmpty())
            }
        }
    }

    @Test fun groupSwitchClearsOldAdapterRowsSynchronously() {
        launchGroup("A").use { scenario ->
            scenario.onActivity { activity ->
                val adapter = mapAdapter(activity)
                adapter.submit(
                    markers = listOf(lockedMarker("A", "visible-A")),
                    areas = emptyList(),
                    liveTrails = emptyList(),
                    resources = activity.resources,
                    showMarkers = true,
                    showAreas = true,
                    hiddenMarkers = emptySet(),
                    hiddenAreas = emptySet(),
                    selectedTrailId = null,
                    markerVoteCounts = emptyMap(),
                    areaVoteCounts = emptyMap(),
                    liveTrailOwners = emptyMap(),
                    liveTrailStaleIds = emptySet(),
                    liveTrailReconnectedUntil = emptyMap(),
                    currentActorDid = DeviceIdProvider.get(activity),
                )
                assertTrue(adapter.items().any {
                    it is MapListAdapter.MapListItem.Marker && it.entity.gid == "A"
                })

                deliverNewIntent(activity, "B")

                assertFalse(adapter.items().any {
                    it is MapListAdapter.MapListItem.Marker && it.entity.gid == "A"
                })
            }
        }
    }

    @Test fun cancelledRetryRunnableDoesNotStartPointQueryAfterGroupSwitch() {
        val queryCount = AtomicInteger()
        val firstResultDelivered = CountDownLatch(1)
        val scheduled = AtomicReference<Runnable>()
        GroupMapActivityTestSeams.trailPointLoader = {
            queryCount.incrementAndGet()
            emptyList()
        }
        GroupMapActivityTestSeams.delayedScheduler = { runnable, _ -> scheduled.set(runnable) }
        GroupMapActivityTestSeams.onTrailPointResultDelivered = firstResultDelivered::countDown

        launchGroup("A").use { scenario ->
            scenario.onActivity { activity ->
                prepareSelectedTrail(activity, liveTrail("A", "X"))
                invokeSelectedTrailLoad(activity, "A", "X")
            }
            assertTrue(
                "initial empty point result was not delivered",
                firstResultDelivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertNotNull("retry was not scheduled", scheduled.get())
            assertEquals(1, queryCount.get())

            scenario.onActivity { activity ->
                deliverNewIntent(activity, "B")
                scheduled.get().run()
            }
            instrumentation.waitForIdleSync()
            assertEquals("stale retry started another point query", 1, queryCount.get())
        }
    }

    @Test fun sameRawTrailIdCompletionFromOldGroupCannotReplaceNewGroupCache() {
        val queryStarted = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        val resultDelivered = CountDownLatch(1)
        val stalePoint = trailPoint("X", 1, 10.0, 106.0)
        val currentPoint = trailPoint("X", 2, 11.0, 107.0)
        GroupMapActivityTestSeams.trailPointLoader = {
            queryStarted.countDown()
            releaseQuery.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            listOf(stalePoint)
        }
        GroupMapActivityTestSeams.onTrailPointResultDelivered = resultDelivered::countDown

        launchGroup("A").use { scenario ->
            scenario.onActivity { activity ->
                prepareSelectedTrail(activity, liveTrail("A", "X"))
                invokeSelectedTrailLoad(activity, "A", "X")
            }
            assertTrue("A/X point query did not start", queryStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            scenario.onActivity { activity ->
                deliverNewIntent(activity, "B")
                prepareSelectedTrail(activity, liveTrail("B", "X"))
                field(activity, "trailPointsById").set(activity, mapOf("X" to listOf(currentPoint)))
            }
            releaseQuery.countDown()
            assertTrue("A/X point result was not delivered", resultDelivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()

            scenario.onActivity { activity ->
                @Suppress("UNCHECKED_CAST")
                val points = field(activity, "trailPointsById").get(activity)
                    as Map<String, List<TrailPointEntity>>
                @Suppress("UNCHECKED_CAST")
                val trails = field(activity, "currentLiveTrails").get(activity) as List<LiveTrailEntity>
                assertEquals(listOf(currentPoint), points["X"])
                assertEquals("X", field(activity, "selectedTrailId").get(activity))
                assertTrue(trails.all { it.gid == "B" })
            }
        }
    }

    private fun launchGroup(gid: String): ActivityScenario<GroupMapActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ActivityScenario.launch(groupIntent(context, gid))
    }

    private fun groupIntent(context: Context, gid: String) =
        Intent(context, GroupMapActivity::class.java)
            .putExtra(GroupMapActivity.EXTRA_GID, gid)
            .putExtra(GroupMapActivity.EXTRA_GROUP_NAME, gid)

    private fun openMarkerDialog(activity: GroupMapActivity, marker: MapMarkerEntity) {
        activity.javaClass.declaredMethods.single {
            it.name == "showMarkerDialog" && it.parameterCount == 5
        }.also { it.isAccessible = true }
            .invoke(activity, marker.gid, marker, null, null, null)
    }

    private fun openAreaDialog(activity: GroupMapActivity, area: MapAreaEntity) {
        activity.javaClass.declaredMethods.single {
            it.name == "showAreaDialog" && it.parameterCount == 4
        }.also { it.isAccessible = true }
            .invoke(activity, area.gid, area, null, null)
    }

    private fun invokeSelectedTrailLoad(activity: GroupMapActivity, gid: String, trailId: String) {
        val generation = field(activity, "mapGroupScopeGeneration").getLong(activity)
        activity.javaClass.declaredMethods.single {
            it.name == "loadSelectedTrailPointsWithRetry" && it.parameterCount == 6
        }.also { it.isAccessible = true }
            .invoke(activity, gid, generation, trailId, mapAdapter(activity), false, true)
    }

    private fun deliverNewIntent(activity: GroupMapActivity, gid: String) {
        activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java)
            .also { it.isAccessible = true }
            .invoke(activity, groupIntent(activity, gid))
    }

    private fun prepareSelectedTrail(activity: GroupMapActivity, trail: LiveTrailEntity) {
        field(activity, "currentLiveTrails").set(activity, listOf(trail))
        field(activity, "selectedTrailId").set(activity, trail.trail_id)
    }

    private fun currentMapDialog(activity: GroupMapActivity): AlertDialog =
        openMapDialogs(activity).single() as AlertDialog

    @Suppress("UNCHECKED_CAST")
    private fun openMapDialogs(activity: GroupMapActivity): Set<Dialog> =
        field(activity, "openMapScopeDialogs").get(activity) as Set<Dialog>

    private fun mapAdapter(activity: GroupMapActivity): MapListAdapter =
        (activity.findViewById<RecyclerView>(R.id.map_items_list).adapter as MapListAdapter)

    private fun MapListAdapter.items(): List<MapListAdapter.MapListItem> =
        (0 until itemCount).mapNotNull(::getItemAt)

    private fun AlertDialog.text(id: Int): String =
        findViewById<EditText>(id)?.text?.toString().orEmpty()

    private fun AlertDialog.radio(id: Int): RadioButton =
        requireNotNull(findViewById(id))

    private fun field(activity: GroupMapActivity, name: String): Field =
        activity.javaClass.getDeclaredField(name).also { it.isAccessible = true }

    private fun lockedMarker(gid: String, id: String): MapMarkerEntity {
        val now = System.currentTimeMillis() / 1000L
        val created = now - 700L
        return MapMarkerEntity(
            gid = gid,
            marker_id = id,
            layer_id = "default",
            lat = 10.0,
            lon = 106.0,
            radius_m = 25.0,
            label = "test",
            note = "owner note",
            kind = "other",
            confidence = 1,
            status = "active",
            created_t = created,
            updated_t = created,
            actor_did = "did:example:other",
            expires_at = created + 1_200L,
            last_event_id = "event-$id",
            last_event_t = created,
            last_event_lt = created,
            last_event_actor = "did:example:other",
        )
    }

    private fun lockedArea(gid: String, id: String): MapAreaEntity {
        val now = System.currentTimeMillis() / 1000L
        val created = now - 700L
        return MapAreaEntity(
            gid = gid,
            area_id = id,
            layer_id = "default",
            geometry_json = """{"type":"Polygon","coordinates":[[[106.0,10.0],[106.01,10.0],[106.0,10.01],[106.0,10.0]]]}""",
            label = "test",
            note = "owner note",
            kind = "other",
            confidence = 1,
            status = "active",
            created_t = created,
            updated_t = created,
            actor_did = "did:example:other",
            expires_at = created + 1_200L,
            last_event_id = "event-$id",
            last_event_t = created,
            last_event_lt = created,
            last_event_actor = "did:example:other",
        )
    }

    private fun vote(
        gid: String,
        itemId: String,
        itemType: String,
        actorDid: String,
        note: String,
        confidence: Int,
    ): MapItemVoteEntity {
        val now = System.currentTimeMillis() / 1000L
        return MapItemVoteEntity(
            gid = gid,
            item_id = itemId,
            item_type = itemType,
            actor_did = actorDid,
            note = note,
            confidence = confidence,
            created_t = now,
            updated_t = now,
        )
    }

    private fun liveTrail(gid: String, trailId: String): LiveTrailEntity =
        LiveTrailEntity(
            trail_id = trailId,
            gid = gid,
            owner_did = "did:example:owner",
            name = "Trail $trailId",
            share_scope = "group",
            share_to_json = null,
            start_t = 1L,
            end_t = null,
            point_count = 1,
            dist_m = 0f,
            active = 1,
            accepted = 1,
            pending_expires_t = null,
            updated_t = 1L,
            last_event_id = "trail-event-$gid-$trailId",
            last_event_t = 1L,
            last_event_lt = 1L,
            last_event_actor = "did:example:owner",
        )

    private fun trailPoint(
        trailId: String,
        index: Int,
        lat: Double,
        lon: Double,
    ) = TrailPointEntity(
        tid = trailId,
        idx = index,
        lat = lat,
        lon = lon,
        t = index.toLong(),
        acc = null,
        spd = null,
        mm = 0,
    )

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
