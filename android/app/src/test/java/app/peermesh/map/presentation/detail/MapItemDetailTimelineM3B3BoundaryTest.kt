package app.peermesh.map.presentation.detail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compile-time/source boundary skeleton for M3B3 production integration. */
class MapItemDetailTimelineM3B3BoundaryTest {
    @Test fun activityOwnsTwoIndependentControllerLifecycles() {
        val source = projectRoot()
            .resolve("app/src/main/java/app/peermesh/map/presentation/detail/MapItemDetailActivity.kt")
            .readText()

        assertTrue(source.contains("MapItemDetailShellController?"))
        assertTrue(source.contains("MapItemTimelineController?"))
        assertTrue(source.contains("timelineController?.close()"))
        assertTrue(source.contains("controller?.close()"))
        assertTrue(source.contains("if (viewLifecycleEnded) return"))
        assertFalse(source.contains("MapItemDetailShellState(") && source.contains("timelineEntries"))
    }

    @Test fun acceptedFeedbackIsTheOnlyAutomaticTimelineRefreshHook() {
        val activity = projectRoot()
            .resolve("app/src/main/java/app/peermesh/map/presentation/detail/MapItemDetailActivity.kt")
            .readText()
        val policy = projectRoot()
            .resolve("app/src/main/java/app/peermesh/map/presentation/detail/MapItemDetailTimelinePresentation.kt")
            .readText()

        assertTrue(policy.contains("feedback !is MapItemDetailShellFeedback.Accepted"))
        assertTrue(activity.contains("timelineCoordinator.shouldRefreshForAcceptedFeedback(feedback)"))
        assertTrue(activity.contains("timelineController?.refreshFirstPage()"))
        assertFalse(activity.contains("Toast") && activity.contains("contains(\"Saved\")"))
    }

    @Test fun adapterDoesNotOwnMergeSortOrCursorPolicy() {
        val source = projectRoot()
            .resolve("app/src/main/java/app/peermesh/map/presentation/timeline/MapItemTimelineAdapter.kt")
            .readText()

        listOf("mergePolicy", "nextCursor", "sortedBy", "distinctBy", "notifyDataSetChanged").forEach { forbidden ->
            assertFalse("Adapter must not own '$forbidden'", source.contains(forbidden))
        }
        assertTrue(source.contains("oldItem.entryId == newItem.entryId"))
        assertTrue(source.contains("oldItem == newItem"))
        assertTrue(source.contains("submitList(entries.toList())"))
    }

    @Test fun activityRoutesRetriesAndFormatsOnlyLocalizedOptionalFields() {
        val source = projectRoot()
            .resolve("app/src/main/java/app/peermesh/map/presentation/detail/MapItemDetailActivity.kt")
            .readText()

        assertTrue(source.contains("MapItemTimelineRetryOperation.LOAD_INITIAL -> timelineController?.loadInitial()"))
        assertTrue(source.contains("MapItemTimelineRetryOperation.REFRESH_FIRST_PAGE -> timelineController?.refreshFirstPage()"))
        assertTrue(source.contains("timelineController?.loadOlder()"))
        assertTrue(source.contains("entry.informationSource?.let"))
        assertTrue(source.contains("entry.updateDisposition?.let"))
        assertTrue(source.contains("entry.targetLifecycle?.let"))
        assertTrue(source.contains("entry.note?.takeIf { it.isNotBlank() }"))
        assertTrue(source.contains("joinToString(\"\\n\")"))
        assertFalse(source.contains("entry.type.name"))
    }

    @Test fun activityUsesPresentationCoordinatorForIndependentManualRefreshAndStartup() {
        val source = projectRoot()
            .resolve("app/src/main/java/app/peermesh/map/presentation/detail/MapItemDetailActivity.kt")
            .readText()

        assertTrue(source.contains("timelineCoordinator.dispatchManualRefresh("))
        assertTrue(source.contains("refreshDetail = { controller?.refresh() }"))
        assertTrue(source.contains("refreshTimelineFirstPage = { timelineController?.refreshFirstPage() }"))
        assertTrue(source.contains("MapItemDetailTimelineStartup.start("))
        assertTrue(source.contains("startDetail = {"))
        assertTrue(source.contains("startTimeline = {"))
    }

    @Test fun invalidNavigationReturnsBeforeEitherControllerIsCreated() {
        val source = projectRoot()
            .resolve("app/src/main/java/app/peermesh/map/presentation/detail/MapItemDetailActivity.kt")
            .readText()
        val invalidReturn = source.indexOf("if (identity == null)")
        val startup = source.indexOf("MapItemDetailTimelineStartup.start")

        assertTrue(invalidReturn >= 0)
        assertTrue(invalidReturn < startup)
        assertFalse(source.contains("TIMELINE_PAGE_SIZE"))
        assertFalse(source.contains("pageSize ="))
    }

    private fun projectRoot(): File {
        val current = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(current) { it.parentFile }
            .firstOrNull { it.resolve("settings.gradle.kts").exists() }
            ?: error("Android project root not found from $current")
    }
}
