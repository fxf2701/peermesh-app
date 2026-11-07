package app.peermesh

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.map.application.creation.MapCreationShape
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.rendering.GroupMapController
import app.peermesh.map.application.rendering.GroupMapState
import app.peermesh.map.application.rendering.GroupMapStateListener
import app.peermesh.map.application.rendering.GroupMapViewportRequest
import app.peermesh.map.presentation.creation.MapCreationController
import app.peermesh.map.presentation.detail.DefaultMapItemDetailNavigationArguments
import app.peermesh.map.presentation.detail.MapItemDetailActivity
import app.peermesh.map.presentation.map.navigation.MapItemNavigationCoordinator
import app.peermesh.map.presentation.map.navigation.MapItemNavigationState
import java.lang.reflect.Field
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupMapActivityM3D4RuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val permissionsGrantedByTest = mutableSetOf<String>()

    @Before fun setUp() {
        GroupMapActivityTestSeams.reset()
        GroupMapActivityTestSeams.bypassFeatureGate = true
        GroupMapActivityTestSeams.suppressBackgroundMapLoads = true
        grantMissingRuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION)
        grantMissingRuntimePermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    @After fun tearDown() {
        GroupMapActivityTestSeams.reset()
        permissionsGrantedByTest.forEach(::revokeRuntimePermission)
    }

    @Test fun exactTypedIdentityDuplicateDraftReturnAndDestructionLifecycle() {
        val markerMonitor = instrumentation.addMonitor(MapItemDetailActivity::class.java.name, null, false)
        val marker = MapItemIdentity("g", MapItemType.MARKER, "same")
        val area = MapItemIdentity("g", MapItemType.AREA, "same")
        var destroyedActivity: GroupMapActivity? = null

        launchGroup().use { scenario ->
            val controller = AcceptingController()
            scenario.onActivity { activity ->
                field(activity, "groupMapController").set(activity, controller)
                activity.requestPersistedMapItemDetail(marker)
                activity.requestPersistedMapItemDetail(area)
                assertEquals(
                    "selections=${controller.selections}, foreground=${GroupMapActivity.isForeground}",
                    MapItemNavigationState.AwaitingReturn(marker),
                    navigationCoordinator(activity).currentState(),
                )
            }

            val markerDetail = instrumentation.waitForMonitorWithTimeout(markerMonitor, TIMEOUT_MS)
            assertEquals(marker, DefaultMapItemDetailNavigationArguments.decode(markerDetail.intent))
            assertEquals(listOf(marker), controller.selections)
            assertEquals(1, markerMonitor.hits)
            instrumentation.runOnMainSync { markerDetail.finish() }
            instrumentation.waitForIdleSync()
            awaitNavigationState(scenario, MapItemNavigationState.Ready)

            val areaMonitor = instrumentation.addMonitor(MapItemDetailActivity::class.java.name, null, false)
            scenario.onActivity { activity -> activity.requestPersistedMapItemDetail(area) }
            val areaDetail = instrumentation.waitForMonitorWithTimeout(areaMonitor, TIMEOUT_MS)
            assertEquals(area, DefaultMapItemDetailNavigationArguments.decode(areaDetail.intent))
            assertEquals(listOf(marker, area), controller.selections)
            assertEquals(1, areaMonitor.hits)
            instrumentation.runOnMainSync { areaDetail.finish() }
            instrumentation.waitForIdleSync()
            awaitNavigationState(scenario, MapItemNavigationState.Ready)

            scenario.onActivity { activity ->
                creationController(activity).begin(
                    "g",
                    MapItemCategory.SITUATION,
                    MapCreationShape.MARKER,
                )
                activity.requestPersistedMapItemDetail(marker)
                assertEquals(listOf(marker, area), controller.selections)
                assertEquals(MapItemNavigationState.Ready, navigationCoordinator(activity).currentState())
                destroyedActivity = activity
            }
        }

        val destructionMonitor = instrumentation.addMonitor(MapItemDetailActivity::class.java.name, null, false)
        instrumentation.runOnMainSync {
            checkNotNull(destroyedActivity).requestPersistedMapItemDetail(marker)
        }
        assertTrue(checkNotNull(destroyedActivity).isDestroyed)
        assertEquals(0, destructionMonitor.hits)
        assertEquals(
            MapItemNavigationState.Closed,
            navigationCoordinator(checkNotNull(destroyedActivity)).currentState(),
        )
        assertNull(instrumentation.waitForMonitorWithTimeout(destructionMonitor, 250L))
    }

    private fun launchGroup(): ActivityScenario<GroupMapActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ActivityScenario.launch(
            Intent(context, GroupMapActivity::class.java)
                .putExtra(GroupMapActivity.EXTRA_GID, "g")
                .putExtra(GroupMapActivity.EXTRA_GROUP_NAME, "Group"),
        )
    }

    private fun grantMissingRuntimePermission(permission: String) {
        if (instrumentation.targetContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${BuildConfig.APPLICATION_ID} $permission",
        ).close()
        permissionsGrantedByTest += permission
    }

    private fun revokeRuntimePermission(permission: String) {
        instrumentation.uiAutomation.executeShellCommand(
            "pm revoke ${BuildConfig.APPLICATION_ID} $permission",
        ).close()
    }

    private fun awaitNavigationState(
        scenario: ActivityScenario<GroupMapActivity>,
        expected: MapItemNavigationState,
    ) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var actual: MapItemNavigationState? = null
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                actual = navigationCoordinator(activity).currentState()
            }
            if (actual == expected) return
            instrumentation.waitForIdleSync()
            SystemClock.sleep(50L)
        }
        fail("Expected navigation state $expected but was $actual")
    }

    private fun navigationCoordinator(activity: GroupMapActivity): MapItemNavigationCoordinator =
        field(activity, "mapItemNavigationCoordinator").get(activity) as MapItemNavigationCoordinator

    private fun creationController(activity: GroupMapActivity): MapCreationController =
        field(activity, "mapCreationController").get(activity) as MapCreationController

    private fun field(activity: GroupMapActivity, name: String): Field =
        activity.javaClass.getDeclaredField(name).also { it.isAccessible = true }

    private class AcceptingController : GroupMapController {
        private var state = GroupMapState.initial()
        val selections = mutableListOf<MapItemIdentity>()

        override fun currentState(): GroupMapState = state
        override fun refresh(request: GroupMapViewportRequest) = Unit
        override fun select(identity: MapItemIdentity?) {
            if (identity != null) selections += identity
            state = state.copy(selection = state.selection.copy(selectedIdentity = identity))
        }
        override fun focus(identity: MapItemIdentity?) = Unit
        override fun reset() {
            state = GroupMapState.initial()
        }
        override fun addListener(listener: GroupMapStateListener) = Unit
        override fun removeListener(listener: GroupMapStateListener) = Unit
        override fun close() = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
