package app.peermesh.map.presentation.map.runtime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmdroidMapRuntimeRendererM3D4BoundaryTest {
    private val source = File(
        "src/main/java/app/peermesh/map/presentation/map/runtime/" +
            "OsmdroidMapRuntimeRenderer.kt",
    ).readText()

    @Test fun everyPersistedClickableArtifactRoutesItsExactTypedModelIdentity() {
        assertTrue(source.contains("clickListener.onItemClicked(model.identity)"))
        assertTrue(source.contains("createLabel(model.identity"))
        assertTrue(source.contains("relatedObject = model.identity"))
        assertTrue(source.contains("registry.put(model.identity"))
    }

    @Test fun radiusCannotNavigateAndRendererNeverLaunchesAndroidDetail() {
        val radius = source.substringAfter("private fun createRadius")
            .substringBefore("private fun updateRadius")
        assertFalse(radius.contains("clickListener"))
        assertFalse(source.contains("startActivity"))
        assertFalse(source.contains("MapItemDetailActivity"))
    }

    @Test fun removalAndCloseDetachOwnedArtifactsAndMakeCloseTerminal() {
        assertTrue(source.contains("registry.remove(identity)?.let(::removeArtifacts)"))
        assertTrue(source.contains("removed.forEach(::removeArtifacts)"))
        assertTrue(source.contains("if (closed) return"))
        assertTrue(source.contains("closed = true"))
        assertFalse(source.contains("OsmdroidMapCreationDraftRenderer"))
    }
}
