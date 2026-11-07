package app.peermesh.map.presentation.community

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapItemCommunityM3C4BoundaryTest {
    @Test fun productionDependencyGuardRejectsForbiddenImportsAndQualifiedReferences() {
        val root = projectRoot()
        val files = root.resolve("app/src/main/java/app/peermesh/map/presentation/community")
            .walkTopDown().filter { it.extension == "kt" }.toList() +
            root.resolve("app/src/main/java/app/peermesh/map/composition/MapItemCommunityPresentationFactory.kt")
        val forbidden = listOf(
            "app.peermesh.data.db", "androidx.room", "RoomMapEventProcessor",
            "RoomMapEventWriter", "LocalMapEventWriter", "RoomMapItemActionQueryRepository",
            "app.peermesh.map.data", "app.peermesh.ble", "app.peermesh.relay",
            "app.peermesh.transport",
        )
        files.forEach { file ->
            val source = codeOnly(file.readText())
            forbidden.forEach { token ->
                assertFalse("Forbidden reference '$token' in ${file.name}", source.contains(token))
            }
        }
    }

    @Test fun activityKeepsOnlyTwoControllers() {
        val source = projectRoot()
            .resolve("app/src/main/java/app/peermesh/map/presentation/detail/MapItemDetailActivity.kt")
            .readText()
        assertTrue(source.contains("MapItemDetailShellController?"))
        assertTrue(source.contains("MapItemTimelineController?"))
        assertFalse(source.contains("VerificationController"))
        assertFalse(source.contains("FreshnessController"))
        assertFalse(source.contains("ParticipationController"))
        assertFalse(source.contains("NotePreviewController"))
    }

    @Test fun activityReprojectsBothSnapshotsWithoutCollapsingTimelineProvenance() {
        val source = projectRoot()
            .resolve("app/src/main/java/app/peermesh/map/presentation/detail/MapItemDetailActivity.kt")
            .readText()
        assertTrue(source.contains("detailContent = detailState.content"))
        assertTrue(source.contains("timeline = communityTimelineInput(latestTimelinePresentation)"))
        assertTrue(source.contains("latestDetailState = state"))
        assertTrue(source.contains("latestTimelinePresentation = presentation"))
        assertTrue(source.contains("MapItemCommunityConfirmCtaAction.open(::openInteraction)"))
    }

    private fun projectRoot(): File {
        val current = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(current) { it.parentFile }
            .firstOrNull { it.resolve("settings.gradle.kts").exists() }
            ?: error("Android project root not found from $current")
    }

    private fun codeOnly(source: String): String = source
        .replace(Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)), "")
        .replace(Regex("//.*$", setOf(RegexOption.MULTILINE)), "")
        .replace(Regex("\"(?:\\\\.|[^\"])*\""), "\"\"")
}
