package app.peermesh.map.presentation.map.resources

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMapVisualResourceResolverM3D5BTest {
    private val source = File(
        "src/main/java/app/peermesh/map/presentation/map/resources/" +
            "AndroidMapVisualResourceResolver.kt",
    ).readText()

    @Test fun persistedMarkerArtworkCarriesKindAndCategoryCues() {
        assertTrue(source.contains("SemanticMarkerDrawable"))
        assertTrue(source.contains("MapKinds.labelForKey(spec.content.normalizedKind)"))
        assertTrue(source.contains("MapCategoryVisualStyle.SITUATION -> \"!\""))
        assertTrue(source.contains("MapCategoryVisualStyle.COORDINATION -> \"↗\""))
        assertTrue(source.contains("canvas.drawOval"))
        assertTrue(source.contains("canvas.drawRoundRect"))
        assertTrue(source.contains("22f * density"))
        assertTrue(source.contains("19f * density"))
    }

    @Test fun confirmationAndResolutionHaveNonColorMarkerCues() {
        assertTrue(source.contains("MapSituationEvidenceVisualStyle.CURRENT_EVIDENCE_PRESENT -> \"✓\""))
        assertTrue(source.contains("MapLifecycleVisualStyle.RESOLVED -> \"×\""))
        assertTrue(source.contains("spec.visual.lifecycle == MapLifecycleVisualStyle.RESOLVED ->"))
        assertTrue(source.contains("DashPathEffect(floatArrayOf(7f * density, 3f * density)"))
    }

    @Test fun resolvedStyleWinsOverCurrentEvidenceColorAndDescriptionsNameKind() {
        assertTrue(source.contains("spec.visual.lifecycle == MapLifecycleVisualStyle.RESOLVED -> darken(base)"))
        assertTrue(source.contains("kindDescription(spec.content.normalizedKind)?.let { parts += it }"))
        assertTrue(source.contains("MapAccessibilityStateToken.CURRENT_EVIDENCE_PRESENT"))
        assertTrue(source.contains("MapAccessibilityStateToken.RESOLVED"))
    }

    @Test fun markerLabelsAppearBelowTheSemanticIconFrame() {
        assertTrue(source.contains("Place it\n                // below the semantic icon"))
        assertTrue(source.contains("spec.geometry is MapSceneGeometry.Marker ||"))
        assertTrue(source.contains("half a badge-height closer to their"))
        assertTrue(source.contains("17f else 14f"))
        assertTrue(source.contains("TextBadgeDrawable(context, preview, palette.outline, leadingInsetPx, topInsetPx)"))
    }

    @Test fun markerAndAreaLabelsReserveVerticalSpaceBelowTheirIcons() {
        assertTrue(source.contains("29f else 26f"))
        assertTrue(source.contains("TextBadgeDrawable(context, preview, palette.outline, leadingInsetPx, topInsetPx)"))
    }

    @Test fun areasHaveCenteredKindEmojiInsideADistinctDashedCircle() {
        assertTrue(source.contains("kindIcon = areaKindDrawable(spec, palette)"))
        assertTrue(source.contains("private class AreaKindDrawable"))
        assertTrue(source.contains("The emoji stays at the polygon centroid"))
        assertTrue(source.contains("color = Color.rgb(70, 70, 70)"))
        assertTrue(source.contains("DashPathEffect(floatArrayOf(6f * density, 4f * density)"))
        assertTrue(source.contains("if (spec.visual.prominence == MapItemProminence.EMPHASIZED) 50f else 44f"))
        assertTrue(source.contains("override fun getIntrinsicWidth()"))
        assertTrue(source.contains("else {\n                22f * density"))
    }

    @Test fun areasCarryTheSameCurrentAndResolvedStatusCueAsMarkers() {
        assertTrue(source.contains("Match marker evidence/lifecycle affordances"))
        assertTrue(source.contains("spec.visual.lifecycle == MapLifecycleVisualStyle.RESOLVED -> \"×\""))
        assertTrue(source.contains("spec.visual.situationEvidence == MapSituationEvidenceVisualStyle.CURRENT_EVIDENCE_PRESENT -> \"✓\""))
        assertTrue(source.contains("spec.visual.coordinationParticipation == MapCoordinationParticipationVisualStyle.ACKNOWLEDGED -> \"•\""))
    }
}
