package app.peermesh.map.presentation.detail

import app.peermesh.map.application.detail.MapItemDetail
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.interaction.MapItemInteractionsAvailability
import app.peermesh.map.application.interaction.MapInteractionType

/**
 * UI-owned state for the minimal M3A4 detail shell.
 *
 * Persisted map truth must always come from LoadMapItemDetail. This state must never be written
 * back as canonical or domain state and must not optimistically mutate lifecycle or aggregates.
 */
data class MapItemDetailShellState(
    val identity: MapItemIdentity,
    val content: MapItemDetailShellContent = MapItemDetailShellContent.InitialLoading,
    val availability: MapItemInteractionsAvailability? = null,
    val activeForm: MapItemInteractionForm? = null,
    val submittingInteraction: MapInteractionType? = null,
    val feedback: MapItemDetailShellFeedback? = null,
) {
    val isSubmissionInFlight: Boolean
        get() = submittingInteraction != null
}

sealed interface MapItemDetailShellContent {
    data object InitialLoading : MapItemDetailShellContent

    data class Loaded(
        val detail: MapItemDetail,
        val enrichment: DomainEnrichmentState,
    ) : MapItemDetailShellContent

    data class InvalidDomainEnrichment(
        val canonical: app.peermesh.map.application.detail.MapItemCanonicalPresentation,
    ) : MapItemDetailShellContent

    data object TargetUnavailable : MapItemDetailShellContent

    data object InvalidItemData : MapItemDetailShellContent

    data object ReadFailure : MapItemDetailShellContent
}

enum class DomainEnrichmentState {
    AVAILABLE,
    MISSING,
    UNRESOLVED_LEGACY,
}
