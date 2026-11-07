package app.peermesh.map.application.participation

import app.peermesh.core.map.MapItemType
import app.peermesh.map.application.detail.MapItemIdentity

/**
 * M3C3 UI-safe projection of current Coordination participation.
 *
 * Current acknowledgement evidence comes only from the accepted M2-backed
 * detail projection. This contract is presentation data, never semantic
 * authority, and must not be reconstructed from timeline rows.
 */
data class MapCoordinationParticipationPresentation(
    val identity: MapItemIdentity,
    val targetShape: MapCoordinationTargetShape,
    val categoryOrigin: MapCoordinationCategoryOrigin,
    val evidence: MapCoordinationAcknowledgementEvidence,
    val limitation: MapCoordinationParticipationLimitation?,
) {
    init {
        require(targetShape == identity.itemType.toCoordinationTargetShape()) {
            "target shape must match map item identity"
        }
    }
}

enum class MapCoordinationTargetShape {
    MARKER,
    AREA,
}

/** UI-safe origin vocabulary; deliberately does not expose persistence types. */
enum class MapCoordinationCategoryOrigin {
    EXPLICIT_DOMAIN_V1,
    LEGACY_KIND_DERIVED,
}

sealed interface MapCoordinationAcknowledgementEvidence {
    data class Present(val actorCount: Int) : MapCoordinationAcknowledgementEvidence {
        init {
            require(actorCount > 0) { "current acknowledgement actor count must be positive" }
        }
    }

    data object Absent : MapCoordinationAcknowledgementEvidence
}

enum class MapCoordinationParticipationLimitation {
    /**
     * Participation is descriptive only. It does not prove correctness,
     * completion, delivery, trust, ranking, quorum, or verification.
     */
    ACKNOWLEDGEMENT_IS_NON_VERIFYING,
}

private fun MapItemType.toCoordinationTargetShape(): MapCoordinationTargetShape = when (this) {
    MapItemType.MARKER -> MapCoordinationTargetShape.MARKER
    MapItemType.AREA -> MapCoordinationTargetShape.AREA
}
