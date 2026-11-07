package app.peermesh.map.application.detail

sealed interface MapItemDetailLoadResult {
    data class Found(val detail: MapItemDetail) : MapItemDetailLoadResult

    data class CanonicalOnly(
        val detail: MapItemDetail,
        val reason: CanonicalOnlyReason,
    ) : MapItemDetailLoadResult

    data class UnresolvedLegacy(val detail: MapItemDetail) : MapItemDetailLoadResult

    data class InvalidIdentity(val reason: InvalidIdentityReason) : MapItemDetailLoadResult

    data object CanonicalItemMissing : MapItemDetailLoadResult

    data object InvalidCanonicalPersistedState : MapItemDetailLoadResult

    /**
     * Canonical data remains available for a future safe read-only shell, while
     * invalid domain enrichment is never converted into unresolved legacy data.
     */
    data class InvalidDomainPersistedState(
        val canonical: MapItemCanonicalPresentation,
    ) : MapItemDetailLoadResult

    data object InternalReadFailure : MapItemDetailLoadResult
}

enum class CanonicalOnlyReason {
    DOMAIN_STATE_MISSING,
}

enum class InvalidIdentityReason {
    BLANK_GROUP_ID,
    BLANK_ITEM_ID,
}
