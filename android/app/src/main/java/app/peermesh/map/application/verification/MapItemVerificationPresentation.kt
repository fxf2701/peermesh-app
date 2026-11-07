package app.peermesh.map.application.verification

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.data.map.MapSituationConfirmationFreshnessBasis

/**
 * M3C1 UI-safe projection of current Situation verification evidence.
 *
 * This is presentation data only. It must never become semantic authority and
 * must never be reconstructed from visible timeline rows.
 */
data class MapItemVerificationPresentation(
    val identity: MapItemIdentity,
    val targetShape: MapVerificationTargetShape,
    val categoryOrigin: MapVerificationCategoryOrigin,
    val lifecycle: MapSituationLifecycleStatus,
    val evidence: MapVerificationEvidence,
    val creatorSource: MapCreatorInformationSourcePresentation,
    val limitation: MapVerificationLimitation?,
    /** M3C2 consumes this safe classification; no ordering tuple is exposed. */
    val freshnessBasis: MapSituationConfirmationFreshnessBasis =
        MapSituationConfirmationFreshnessBasis.Unknown,
) {
    init {
        require(targetShape == identity.itemType.toVerificationTargetShape()) {
            "target shape must match map item identity"
        }
        require(lifecycle == MapSituationLifecycleStatus.ACTIVE || evidence == MapVerificationEvidence.Absent) {
            "non-active situations cannot carry current verification evidence"
        }
        require(
            categoryOrigin != MapVerificationCategoryOrigin.EXPLICIT_DOMAIN_V1 ||
                creatorSource is MapCreatorInformationSourcePresentation.Known,
        ) {
            "explicit-domain situation requires a known creator information source"
        }
        require(
            (targetShape == MapVerificationTargetShape.MARKER && limitation == null) ||
                (targetShape == MapVerificationTargetShape.AREA &&
                    limitation == MapVerificationLimitation.AREA_REPORTED_CONDITION_ONLY),
        ) {
            "verification limitation must match target shape"
        }
    }
}

/** UI-safe origin vocabulary; deliberately does not expose the persistence enum. */
enum class MapVerificationCategoryOrigin {
    EXPLICIT_DOMAIN_V1,
    LEGACY_KIND_DERIVED,
}

enum class MapVerificationTargetShape {
    MARKER,
    AREA,
}

sealed interface MapVerificationEvidence {
    data class Present(
        val actorCount: Int,
        /**
         * Latest confirmation time that is safe to describe as current.
         *
         * It must remain null when the accepted current-state projection cannot
         * prove that the stored timestamp belongs to current evidence.
         */
        val latestCurrentConfirmationAtSeconds: Long?,
    ) : MapVerificationEvidence {
        init {
            require(actorCount > 0) { "current confirmation actor count must be positive" }
            require(latestCurrentConfirmationAtSeconds == null || latestCurrentConfirmationAtSeconds >= 0L) {
                "latest current confirmation time must not be negative"
            }
        }
    }

    data object Absent : MapVerificationEvidence
}

sealed interface MapCreatorInformationSourcePresentation {
    data class Known(val source: MapInformationSource) : MapCreatorInformationSourcePresentation

    /** Recognized legacy Situation whose original source was never explicit. */
    data object UnspecifiedLegacy : MapCreatorInformationSourcePresentation
}

enum class MapVerificationLimitation {
    /** Area confirmation concerns the reported condition, not exact geometry. */
    AREA_REPORTED_CONDITION_ONLY,
}

private fun MapItemType.toVerificationTargetShape(): MapVerificationTargetShape = when (this) {
    MapItemType.MARKER -> MapVerificationTargetShape.MARKER
    MapItemType.AREA -> MapVerificationTargetShape.AREA
}
