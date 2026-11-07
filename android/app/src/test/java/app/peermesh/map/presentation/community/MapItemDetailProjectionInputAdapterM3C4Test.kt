package app.peermesh.map.presentation.community

import app.peermesh.core.map.MapItemType
import app.peermesh.map.application.detail.CanonicalOnlyReason
import app.peermesh.map.application.detail.MapCoordinatesPresentation
import app.peermesh.map.application.detail.MapItemCanonicalAvailability
import app.peermesh.map.application.detail.MapItemDetail
import app.peermesh.map.application.detail.MapItemDetailLoadResult
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.detail.MarkerCanonicalPresentation
import app.peermesh.map.presentation.detail.DomainEnrichmentState
import app.peermesh.map.presentation.detail.MapItemDetailShellContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MapItemDetailProjectionInputAdapterM3C4Test {
    @Test fun loadedStatesMapWithoutReconstructingDetail() {
        val found = MapItemDetailProjectionInputAdapter.adapt(loaded(DomainEnrichmentState.AVAILABLE))
            as MapItemDetailLoadResult.Found
        assertSame(DETAIL, found.detail)

        val canonicalOnly = MapItemDetailProjectionInputAdapter.adapt(loaded(DomainEnrichmentState.MISSING))
            as MapItemDetailLoadResult.CanonicalOnly
        assertSame(DETAIL, canonicalOnly.detail)
        assertEquals(CanonicalOnlyReason.DOMAIN_STATE_MISSING, canonicalOnly.reason)

        val legacy = MapItemDetailProjectionInputAdapter.adapt(loaded(DomainEnrichmentState.UNRESOLVED_LEGACY))
            as MapItemDetailLoadResult.UnresolvedLegacy
        assertSame(DETAIL, legacy.detail)
    }

    @Test fun terminalStatesRemainExactAndLoadingNeverBecomesSuccess() {
        val invalidDomain = MapItemDetailProjectionInputAdapter.adapt(
            MapItemDetailShellContent.InvalidDomainEnrichment(CANONICAL),
        ) as MapItemDetailLoadResult.InvalidDomainPersistedState
        assertSame(CANONICAL, invalidDomain.canonical)
        assertEquals(MapItemDetailLoadResult.CanonicalItemMissing,
            MapItemDetailProjectionInputAdapter.adapt(MapItemDetailShellContent.TargetUnavailable))
        assertEquals(MapItemDetailLoadResult.InvalidCanonicalPersistedState,
            MapItemDetailProjectionInputAdapter.adapt(MapItemDetailShellContent.InvalidItemData))
        assertEquals(MapItemDetailLoadResult.InternalReadFailure,
            MapItemDetailProjectionInputAdapter.adapt(MapItemDetailShellContent.ReadFailure))
        assertNull(MapItemDetailProjectionInputAdapter.adapt(MapItemDetailShellContent.InitialLoading))
    }

    private fun loaded(enrichment: DomainEnrichmentState) =
        MapItemDetailShellContent.Loaded(DETAIL, enrichment)

    companion object {
        private val IDENTITY = MapItemIdentity("group", MapItemType.MARKER, "item")
        private val CANONICAL = MarkerCanonicalPresentation(
            IDENTITY, "situation", "Item", null, MapItemCanonicalAvailability.ACTIVE,
            null, MapCoordinatesPresentation(10.0, 106.0), null,
        )
        private val DETAIL = MapItemDetail(IDENTITY, CANONICAL, null)
    }
}
