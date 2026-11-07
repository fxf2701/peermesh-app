package app.peermesh.map.presentation.detail

import app.peermesh.map.application.detail.LoadMapItemDetail
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.interaction.EvaluateMapItemInteractions
import app.peermesh.map.application.submission.SubmitMapInteraction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapItemDetailShellM3A4BoundaryTest {
    @Test fun controllerDependencyDirectionIsExactlyTheLockedApplicationBoundary() {
        assertEquals(
            setOf(
                MapItemIdentity::class.java,
                LoadMapItemDetail::class.java,
                EvaluateMapItemInteractions::class.java,
                SubmitMapInteraction::class.java,
                MapItemDetailShellExecution::class.java,
                MapItemDetailShellStateObserver::class.java,
            ),
            DefaultMapItemDetailShellController::class.java.declaredConstructors.single().parameterTypes.toSet(),
        )
    }

    @Test fun presentationSourcesDoNotDependOnProtectedDataOrTransportInternals() {
        val sourceRoot = projectRoot().resolve("app/src/main/java/app/peermesh/map/presentation")
        val forbidden = listOf(
            ".data.db.entity.", ".data.dao.", ".data.reducer.", "RoomMapEventProcessor",
            "LocalMapEventWriter", "EventCore", "payload", ".ble.", ".relay.", "Envelope",
        )
        sourceRoot.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            forbidden.forEach { token ->
                assertFalse("Protected dependency '$token' leaked into ${file.name}", text.contains(token))
            }
        }
    }

    @Test fun mapIntegrationPreservesCreationDraftingOwnerAndLegacyEntryPoints() {
        val root = projectRoot()
        val activity = root.resolve("app/src/main/java/app/peermesh/GroupMapActivity.kt").readText()
        val adapter = root.resolve("app/src/main/java/app/peermesh/MapListAdapter.kt").readText()
        listOf("InputMode.MARKER_DRAFT", "InputMode.AREA_DRAFT", "showMarkerDialog(", "showAreaDialog(",
            "requestMarkerRemovalFromList", "requestAreaRemovalFromList").forEach {
            assertTrue("Protected map flow missing: $it", activity.contains(it))
        }
        listOf("map_contribute", "onMarkerEdit", "onAreaEdit", "onMarkerDelete", "onAreaDelete").forEach {
            assertTrue("Legacy/owner list entry missing: $it", adapter.contains(it))
        }
        assertTrue(activity.contains("MapItemIdentity(marker.gid, MapItemType.MARKER"))
        assertTrue(activity.contains("MapItemIdentity(area.gid, MapItemType.AREA"))
    }

    @Test fun phaseIntroducesNoVerificationScoringOrPersistenceSurface() {
        val root = projectRoot()
        val productionFiles = listOf(
            root.resolve("app/src/main/java/app/peermesh/map/presentation/detail"),
            root.resolve("app/src/main/res/layout/activity_map_item_detail.xml"),
        ).flatMap { file -> if (file.isDirectory) file.walkTopDown().filter(File::isFile).toList() else listOf(file) }
        val combined = productionFiles.joinToString("\n") { it.readText() }
        listOf("Community Verification", "trust percentage", "confidence score",
            "@Entity", "@Dao", "Migration(").forEach { token ->
            assertFalse("Out-of-scope surface introduced: $token", combined.contains(token, ignoreCase = true))
        }
        val databaseSource = root.resolve("data/src/main/java/app/peermesh/data/db/AppDatabase.kt").readText()
        assertTrue(databaseSource.contains("version = 55"))
    }

    private fun projectRoot(): File {
        val current = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(current) { it.parentFile }
            .firstOrNull { it.resolve("settings.gradle.kts").exists() }
            ?: error("Android project root not found from $current")
    }
}
