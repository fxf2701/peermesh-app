package app.peermesh.map.application.rendering

import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.scene.MapSceneGeometry

data class MapItemRenderSpec(
    val identity: MapItemIdentity,
    val geometry: MapSceneGeometry,
    val content: MapItemRenderContent,
    val visual: MapItemVisualSpec,
    val interaction: MapItemRenderInteractionSpec,
    val accessibility: MapItemAccessibilitySpec,
)

data class MapItemRenderContent(
    val normalizedKind: String?,
    val label: String?,
)

data class MapItemVisualSpec(
    val category: MapCategoryVisualStyle,
    val lifecycle: MapLifecycleVisualStyle?,
    val temporal: MapTemporalVisualStyle,
    val situationEvidence: MapSituationEvidenceVisualStyle,
    val coordinationParticipation: MapCoordinationParticipationVisualStyle,
    val limitations: Set<MapItemLimitationVisualStyle>,
    val emphasis: MapItemEmphasis,
    val prominence: MapItemProminence,
    val labelMode: MapLabelMode,
    val radiusMode: MapRadiusMode,
)

data class MapItemRenderInteractionSpec(
    val selectable: Boolean,
    val detailOpenable: Boolean,
)

data class MapItemAccessibilitySpec(
    val category: MapAccessibilityCategory,
    val stateTokens: List<MapAccessibilityStateToken>,
)
