package app.peermesh.map.application.rendering

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapRenderingM3D2BoundaryTest {
    @Test fun pureVisualPolicyHasNoAndroidSdkRoomOrRepositoryDependencies() {
        val source = root().resolve(
            "app/src/main/java/app/peermesh/map/application/rendering/DefaultMapItemVisualPolicy.kt",
        ).readText()
        val imports = source.lineSequence().filter { it.startsWith("import ") }.joinToString("\n")
        listOf("android.", "androidx.", "org.osmdroid", ".db.", ".repository.")
            .forEach { assertFalse("visual policy leaked $it", imports.contains(it, ignoreCase = true)) }
    }

    @Test fun runtimeRendererConsumesResolvedModelsAndNeverSemanticOrPersistenceState() {
        val source = root().resolve(
            "app/src/main/java/app/peermesh/map/presentation/map/runtime/OsmdroidMapRuntimeRenderer.kt",
        ).readText()
        val imports = source.lineSequence().filter { it.startsWith("import ") }.joinToString("\n")
        assertTrue(source.contains("AndroidMapRenderModel"))
        listOf("MapSceneItem", "MapMarkerEntity", "MapAreaEntity", "Repository", "Dao")
            .forEach { assertFalse("runtime renderer leaked $it", imports.contains(it, ignoreCase = true)) }
        assertFalse(source.contains("overlays.clear()"))
    }

    private fun root(): File {
        val current = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(current) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").exists() }
    }
}
