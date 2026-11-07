package app.peermesh.map.application.scene

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSceneM3D1BoundaryTest {
    @Test fun sceneApplicationSourcesContainNoRoomAndroidMapSdkOrProductionUiDependencies() {
        val combined = sceneSources().joinToString("\n") { it.readText() }
        listOf(
            "androidx.room",
            "app.peermesh.data.db.dao",
            "app.peermesh.data.db.entity",
            "org.osmdroid",
            "android.view",
            "android.widget",
            "GroupMapActivity",
            ".application.timeline.",
            ".application.submission.",
        ).forEach { token ->
            assertFalse("M3D1 scene boundary leaked $token", combined.contains(token))
        }
    }

    @Test fun sceneModelsContainNoVisualPolicyOrEphemeralRendererState() {
        val modelSources = listOf("MapSceneGeometry.kt", "MapSceneItem.kt", "MapSceneLoadResult.kt")
            .map { sceneRoot().resolve(it).readText() }
            .joinToString("\n")
        listOf("drawable", "color", "alpha", "strokeWidth", "zIndex", "selected", "focused", "Context")
            .forEach { token -> assertFalse("Scene model leaked $token", modelSources.contains(token, ignoreCase = true)) }
    }

    @Test fun loaderUsesOnlyLogicalBatchReadsAndCurrentRoomSchema() {
        val source = sceneRoot().resolve("DefaultLoadMapSceneItems.kt").readText()
        assertTrue(source.contains("canonicalRepository.findBatch("))
        assertTrue(source.contains("domainStateRepository.findBatch("))
        assertFalse(source.contains("canonicalRepository.find("))
        assertFalse(source.contains("domainStateRepository.find("))
        assertTrue(
            projectRoot().resolve("data/src/main/java/app/peermesh/data/db/AppDatabase.kt")
                .readText().contains("version = 55"),
        )
    }

    private fun sceneSources() = sceneRoot().walkTopDown().filter { it.extension == "kt" }.toList()

    private fun sceneRoot() =
        projectRoot().resolve("app/src/main/java/app/peermesh/map/application/scene")

    private fun projectRoot(): File {
        val current = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(current) { it.parentFile }
            .firstOrNull { it.resolve("settings.gradle.kts").exists() }
            ?: error("Android project root not found from $current")
    }
}
