package app.peermesh

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMapActivityM3D4BoundaryTest {
    private val source = File("src/main/java/app/peermesh/GroupMapActivity.kt").readText()

    @Test fun runtimeAndListDetailsUseOneCoordinatorControlledEntryPoint() {
        val markerDetail = source.substringAfter("onMarkerDetail =")
            .substringBefore("onAreaEdit =")
        val areaDetail = source.substringAfter("onAreaDetail =")
            .substringBefore("onViewAllTrails =")
        val runtimeClick = source.substringAfter("clickListener = MapRuntimeItemClickListener")
            .substringBefore("groupMapController =")

        listOf(markerDetail, areaDetail, runtimeClick).forEach { entry ->
            assertTrue(entry.contains("requestPersistedMapItemDetail"))
            assertFalse(entry.contains("startActivity"))
            assertFalse(entry.contains("MapItemDetailActivity.intent"))
            assertFalse(entry.contains("openMapItemDetail"))
        }
        assertFalse(source.contains("openMapItemDetail"))
        assertEquals(1, Regex("MapItemDetailActivity\\.intent\\(").findAll(source).count())
    }

    @Test fun coordinatorReservesBeforeSelectionAndOnlyExactAcceptanceLaunches() {
        val navigation = source.substringAfter("internal fun requestPersistedMapItemDetail")
            .substringBefore("private fun launchMapItemDetail")
        val begin = navigation.indexOf("beginItemClick")
        val reserve = navigation.indexOf("is MapItemNavigationDecision.Reserve")
        val select = navigation.indexOf("groupMapController.select(decision.identity)")
        val exact = navigation.indexOf("selectedIdentity == decision.identity")
        val selectionAccepted = navigation.indexOf("markSelectionAccepted(decision.identity)")
        val launch = navigation.indexOf("launchMapItemDetail(launch.identity)")

        assertTrue(begin >= 0)
        assertTrue(reserve > begin)
        assertTrue(select > reserve)
        assertTrue(exact > select)
        assertTrue(selectionAccepted > exact)
        assertTrue(launch > selectionAccepted)
        assertFalse(navigation.substring(0, reserve).contains("groupMapController.select"))
        assertTrue(navigation.contains("markSelectionFailed(decision.identity)"))
    }

    @Test fun navigationPathHasNoPersistenceOrSemanticProcessingOwner() {
        val navigation = source.substringAfter("internal fun requestPersistedMapItemDetail")
            .substringBefore("private fun launchMapItemDetail")
        listOf(
            "Dao",
            "RoomMapEventProcessor",
            "Reducer",
            "MapSceneItem",
            "MapMarkerEntity",
            "MapAreaEntity",
            "AppDatabase",
        ).forEach { forbidden -> assertFalse(navigation.contains(forbidden)) }
    }

    @Test fun launchBoundaryCatchesOnlyOrdinaryExceptionAndClosesBeforeOwners() {
        val launch = source.substringAfter("private fun launchMapItemDetail")
            .substringBefore("private fun emojiForKind")
        assertTrue(launch.contains("catch (_: Exception)"))
        assertFalse(launch.contains("Throwable"))
        assertFalse(launch.contains("Error"))
        assertEquals(1, Regex("MapItemDetailActivity\\.intent\\(").findAll(launch).count())

        val destroy = source.substringAfter("override fun onDestroy()")
            .substringBefore("override fun onNewIntent")
        assertTrue(
            destroy.indexOf("mapItemNavigationCoordinator.close()") <
                destroy.indexOf("groupMapController.close()"),
        )
    }

    @Test fun returnRefreshIsConsumedOnlyOnResumeAndCoalescedWithPersistedLoad() {
        val resume = source.substringAfter("override fun onResume()")
            .substringBefore("override fun onPause()")
        assertTrue(resume.contains("consumeReturnRefreshRequest()"))
        assertTrue(resume.contains("loadMapItems(gid, adapter)"))
        assertFalse(
            source.substringAfter("private fun refreshSemanticMapRendering()")
                .substringBefore("internal fun requestPersistedMapItemDetail")
                .contains("consumeReturnRefreshRequest"),
        )
    }
}
