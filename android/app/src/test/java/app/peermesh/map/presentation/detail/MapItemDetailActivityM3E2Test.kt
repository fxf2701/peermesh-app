package app.peermesh.map.presentation.detail

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MapItemDetailActivityM3E2Test {
    private val activitySource = File(
        "src/main/java/app/peermesh/map/presentation/detail/MapItemDetailActivity.kt",
    ).readText()
    private val stringsSource = File("src/main/res/values/strings.xml").readText()

    @Test fun coordinationUsesItsOwnActivityHeading() {
        assertTrue(activitySource.contains("renderTimelineHeading(detail.domain?.category)"))
        assertTrue(activitySource.contains("MapItemCategory.COORDINATION"))
        assertTrue(activitySource.contains("R.string.map_detail_timeline_heading_coordination"))
        assertTrue(stringsSource.contains("name=\"map_detail_timeline_heading_coordination\""))
    }

    @Test fun acknowledgementCtaHidesOnlyAfterAcceptedOrAlreadyRecordedOutcome() {
        assertTrue(activitySource.contains("currentUserAcknowledgedCoordination"))
        assertTrue(activitySource.contains("MapItemDetailShellFeedback.AlreadyRecorded"))
        assertTrue(activitySource.contains("entry.type == MapItemActivityType.COORDINATION_ACKNOWLEDGED"))
    }
}
