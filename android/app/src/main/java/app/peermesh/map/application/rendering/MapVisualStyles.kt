package app.peermesh.map.application.rendering

enum class MapItemEmphasis {
    NORMAL,
    SELECTED,
    FOCUSED,
    SELECTED_AND_FOCUSED,
}

enum class MapCategoryVisualStyle {
    SITUATION,
    COORDINATION,
    UNRESOLVED,
    CANONICAL_ONLY,
}

enum class MapLifecycleVisualStyle {
    ACTIVE,
    RESOLVED,
}

enum class MapTemporalVisualStyle {
    CURRENT,
    EXPIRED,
    INTERACTION_CLOSED,
    UNRESOLVED,
}

enum class MapSituationEvidenceVisualStyle {
    CURRENT_EVIDENCE_PRESENT,
    CURRENT_EVIDENCE_ABSENT,
    HISTORICAL_OR_STALE_ONLY,
    UNAVAILABLE,
    NOT_APPLICABLE,
}

enum class MapCoordinationParticipationVisualStyle {
    ACKNOWLEDGED,
    NOT_ACKNOWLEDGED,
    UNAVAILABLE,
    NOT_APPLICABLE,
}

enum class MapItemLimitationVisualStyle {
    CANONICAL_ONLY,
    UNRESOLVED_LEGACY,
    AREA_CONDITION_NOT_BOUNDARY_VERIFICATION,
}

enum class MapItemProminence {
    MUTED,
    STANDARD,
    EMPHASIZED,
}

enum class MapLabelMode {
    HIDDEN,
    SHORT,
}

enum class MapRadiusMode {
    HIDDEN,
    VISIBLE,
}

enum class MapAccessibilityCategory {
    SITUATION,
    COORDINATION,
    UNRESOLVED_ITEM,
}

enum class MapAccessibilityStateToken {
    ACTIVE,
    RESOLVED,
    EXPIRED,
    INTERACTION_CLOSED,
    CURRENT_EVIDENCE_PRESENT,
    NO_CURRENT_EVIDENCE,
    ACKNOWLEDGEMENT_PRESENT,
    NO_ACKNOWLEDGEMENT,
    AREA_BOUNDARY_NOT_VERIFIED,
    LIMITED_INFORMATION,
}
