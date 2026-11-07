package app.peermesh.map.presentation.detail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapItemDetailOwnerAttributionM3E13Test {
    private val activity = File(
        "src/main/java/app/peermesh/map/presentation/detail/MapItemDetailActivity.kt",
    ).readText()
    private val adapter = File(
        "src/main/java/app/peermesh/map/presentation/timeline/MapItemTimelineAdapter.kt",
    ).readText()

    @Test
    fun `timeline resolves activity actors from group profile projections rather than their did`() {
        assertTrue(activity.contains("loadTimelineActorNames(identity)"))
        assertTrue(activity.contains(".userProfileDao()"))
        assertTrue(activity.contains("MapOwnerDisplayNameResolver.resolve("))
        assertTrue(activity.contains("MapOwnerDisplayName.You -> getString(R.string.map_detail_timeline_actor_you)"))
        assertTrue(activity.contains("MapOwnerDisplayName.GroupMember -> getString(R.string.map_detail_timeline_actor_group_member)"))
        assertFalse(activity.contains("entry.actor.displayValue.takeIf"))
        assertFalse(activity.contains("entry.actor.stableId.takeIf"))
    }

    @Test
    fun `timeline rows are rebound when owner name enrichment finishes`() {
        assertTrue(activity.contains("timelineAdapter?.refreshEntryTexts()"))
        assertTrue(adapter.contains("fun refreshEntryTexts()"))
    }

    @Test
    fun `timeline summary uses by actor and relative time`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue(activity.contains("formatTimelineRelativeTime(entry.actionTimestampSeconds)"))
        assertTrue(activity.contains("R.string.map_age_ago_format"))
        assertTrue(strings.contains("map_detail_timeline_entry_summary\">%1\$s by %2\$s %3\$s"))
    }
}
