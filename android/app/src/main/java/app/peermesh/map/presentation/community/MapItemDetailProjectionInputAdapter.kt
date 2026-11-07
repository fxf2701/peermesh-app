package app.peermesh.map.presentation.community

import app.peermesh.map.application.detail.CanonicalOnlyReason
import app.peermesh.map.application.detail.MapItemDetailLoadResult
import app.peermesh.map.presentation.detail.DomainEnrichmentState
import app.peermesh.map.presentation.detail.MapItemDetailShellContent

/**
 * Lossless presentation-boundary adapter from accepted M3A shell content to the
 * already accepted M3C projector input vocabulary.
 *
 * It performs no reads and must not infer category, lifecycle, freshness, or counts.
 */
internal object MapItemDetailProjectionInputAdapter {
    fun adapt(content: MapItemDetailShellContent): MapItemDetailLoadResult? = when (content) {
        MapItemDetailShellContent.InitialLoading -> null
        is MapItemDetailShellContent.Loaded -> when (content.enrichment) {
            DomainEnrichmentState.AVAILABLE -> MapItemDetailLoadResult.Found(content.detail)
            DomainEnrichmentState.MISSING -> MapItemDetailLoadResult.CanonicalOnly(
                detail = content.detail,
                reason = CanonicalOnlyReason.DOMAIN_STATE_MISSING,
            )
            DomainEnrichmentState.UNRESOLVED_LEGACY ->
                MapItemDetailLoadResult.UnresolvedLegacy(content.detail)
        }
        is MapItemDetailShellContent.InvalidDomainEnrichment ->
            MapItemDetailLoadResult.InvalidDomainPersistedState(content.canonical)
        MapItemDetailShellContent.TargetUnavailable -> MapItemDetailLoadResult.CanonicalItemMissing
        MapItemDetailShellContent.InvalidItemData -> MapItemDetailLoadResult.InvalidCanonicalPersistedState
        MapItemDetailShellContent.ReadFailure -> MapItemDetailLoadResult.InternalReadFailure
    }
}
