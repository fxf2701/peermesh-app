package app.peermesh.map.presentation.map.resources

import app.peermesh.core.map.MapItemType
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.rendering.MapAccessibilityCategory
import app.peermesh.map.application.rendering.MapAccessibilityStateToken
import app.peermesh.map.application.rendering.MapCategoryVisualStyle
import app.peermesh.map.application.rendering.MapCoordinationParticipationVisualStyle
import app.peermesh.map.application.rendering.MapItemAccessibilitySpec
import app.peermesh.map.application.rendering.MapItemEmphasis
import app.peermesh.map.application.rendering.MapItemLimitationVisualStyle
import app.peermesh.map.application.rendering.MapItemProminence
import app.peermesh.map.application.rendering.MapItemRenderContent
import app.peermesh.map.application.rendering.MapItemRenderInteractionSpec
import app.peermesh.map.application.rendering.MapItemRenderSpec
import app.peermesh.map.application.rendering.MapItemVisualSpec
import app.peermesh.map.application.rendering.MapLabelMode
import app.peermesh.map.application.rendering.MapLifecycleVisualStyle
import app.peermesh.map.application.rendering.MapRadiusMode
import app.peermesh.map.application.rendering.MapSituationEvidenceVisualStyle
import app.peermesh.map.application.rendering.MapTemporalVisualStyle
import app.peermesh.map.application.scene.MapSceneAreaGeometry
import app.peermesh.map.application.scene.MapSceneCoordinate
import app.peermesh.map.application.scene.MapSceneGeometry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMapVisualResourceResolverM3D4Test {
    @Test fun accessibilityWordingSeparatesCategoryShapeEvidenceAndAcknowledgement() {
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains(">Situation<"))
        assertTrue(strings.contains(">Coordination item<"))
        assertTrue(strings.contains(">Marker<"))
        assertTrue(strings.contains(">Area<"))
        assertTrue(strings.contains("Community confirmation is current"))
        assertTrue(strings.contains("Acknowledgement is present"))
        assertTrue(strings.contains("reported condition, not the exact area boundary"))
        assertFalse(strings.contains("trust percentage", ignoreCase = true))
        assertFalse(strings.contains("threshold", ignoreCase = true))
    }

    @Test fun importantStatesAndSelectionHaveTextualNonColorCues() {
        val strings = File("src/main/res/values/strings.xml").readText()
        listOf(
            "Active",
            "Resolved",
            "Expired",
            "Interaction closed",
            "Selected",
            "Information is limited",
        ).forEach { cue -> assertTrue("missing cue: $cue", strings.contains(">$cue<")) }
    }

    @Test fun longLabelIsBoundedWithoutMutatingCanonicalPresentationInput() {
        val originalLabel = "A".repeat(120)
        val originalGeometry = MapSceneGeometry.Area(
            MapSceneAreaGeometry.Polygon(
                listOf(
                    listOf(
                        MapSceneCoordinate(1.0, 2.0),
                        MapSceneCoordinate(3.0, 4.0),
                    ),
                ),
            ),
        )
        val original = spec(originalLabel, originalGeometry)
        val preview = boundedMapLabelPreview(original.content.label!!)

        assertEquals(MAX_MAP_LABEL_PREVIEW_CHARS, preview.length)
        assertTrue(preview.endsWith("…"))
        assertEquals(originalLabel, original.content.label)
        assertEquals(originalGeometry, original.geometry)
    }

    @Test fun resolverUsesAcceptedSpecAndDoesNotExposeRawGeometryOrDomainTerminology() {
        val source = File(
            "src/main/java/app/peermesh/map/presentation/map/resources/" +
                "AndroidMapVisualResourceResolver.kt",
        ).readText()
        assertTrue(source.contains("spec.accessibility.stateTokens"))
        assertTrue(source.contains("boundedMapLabelPreview"))
        assertFalse(source.contains("geometry_json"))
        assertFalse(source.contains("GeoJSON"))
        assertFalse(source.contains("EventTypes"))
        assertFalse(source.contains("RoomMapEventProcessor"))
    }

    @Test fun listLabelAndNotePreviewsAreVisuallyBoundedWithoutChangingEntityValues() {
        listOf("item_map_marker.xml", "item_map_area.xml").forEach { name ->
            val layout = File("src/main/res/layout/$name").readText()
            assertTrue(layout.contains("android:ellipsize=\"end\""))
            assertTrue(layout.contains("android:maxLines=\"2\""))
            assertTrue(layout.contains("android:maxLines=\"3\""))
        }
    }

    private fun spec(label: String, geometry: MapSceneGeometry) = MapItemRenderSpec(
        identity = MapItemIdentity("g", MapItemType.AREA, "a"),
        geometry = geometry,
        content = MapItemRenderContent("kind", label),
        visual = MapItemVisualSpec(
            category = MapCategoryVisualStyle.SITUATION,
            lifecycle = MapLifecycleVisualStyle.ACTIVE,
            temporal = MapTemporalVisualStyle.CURRENT,
            situationEvidence = MapSituationEvidenceVisualStyle.CURRENT_EVIDENCE_PRESENT,
            coordinationParticipation = MapCoordinationParticipationVisualStyle.NOT_APPLICABLE,
            limitations = setOf(
                MapItemLimitationVisualStyle.AREA_CONDITION_NOT_BOUNDARY_VERIFICATION,
            ),
            emphasis = MapItemEmphasis.SELECTED,
            prominence = MapItemProminence.EMPHASIZED,
            labelMode = MapLabelMode.SHORT,
            radiusMode = MapRadiusMode.HIDDEN,
        ),
        interaction = MapItemRenderInteractionSpec(true, true),
        accessibility = MapItemAccessibilitySpec(
            MapAccessibilityCategory.SITUATION,
            listOf(
                MapAccessibilityStateToken.ACTIVE,
                MapAccessibilityStateToken.CURRENT_EVIDENCE_PRESENT,
                MapAccessibilityStateToken.AREA_BOUNDARY_NOT_VERIFIED,
            ),
        ),
    )
}
