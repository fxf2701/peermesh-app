package app.peermesh.data.map

import app.peermesh.data.db.PersistenceParseResult
import app.peermesh.data.db.entity.MapItemDomainStateEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class MapItemConfirmationFreshnessMapperM3C2Test {
    @Test fun activeExplicitStateWithoutConfirmationProvesNoAcceptedPriorConfirmation() {
        assertBasis(
            MapSituationConfirmationFreshnessBasis.NoAcceptedPriorConfirmationEstablished,
            entity(),
        )
    }

    @Test fun activeConfirmationWithoutLaterSituationChangeIsCurrent() {
        assertBasis(MapSituationConfirmationFreshnessBasis.Current(20), entity(confirmation = order(20), count = 2))
    }

    @Test fun projectedOrdersAtOrBeforeCanonicalAddFailSafeToUnknown() {
        assertBasis(
            MapSituationConfirmationFreshnessBasis.Unknown,
            entity(canonicalAdd = order(100, event = "add"), confirmation = order(50), count = 1),
        )
        assertBasis(
            MapSituationConfirmationFreshnessBasis.Unknown,
            entity(canonicalAdd = order(100, event = "add"), confirmation = order(100, event = "add"), count = 1),
        )
        assertBasis(
            MapSituationConfirmationFreshnessBasis.Unknown,
            entity(canonicalAdd = order(100, event = "add"), update = order(50), material = order(50)),
        )
        assertBasis(
            MapSituationConfirmationFreshnessBasis.Unknown,
            entity(lifecycle = "no_longer_active", canonicalAdd = order(100, event = "add"),
                lifecycleOrder = order(50, event = "resolved"), material = order(50, event = "resolved")),
        )
        assertBasis(
            MapSituationConfirmationFreshnessBasis.Unknown,
            entity(lifecycle = "no_longer_active", canonicalAdd = order(100, event = "add"),
                lifecycleOrder = order(100, event = "add"), material = order(100, event = "add")),
        )
    }

    @Test fun missingCanonicalAddForSituationFailsSafeToUnknown() {
        assertBasis(
            MapSituationConfirmationFreshnessBasis.Unknown,
            entity(canonicalAdd = null, confirmation = order(20), count = 1),
        )
    }

    @Test fun canonicalAddComparisonUsesActorAndEventTieBreakers() {
        val add = order(100, actor = "did:b", event = "add")
        assertBasis(
            MapSituationConfirmationFreshnessBasis.Unknown,
            entity(canonicalAdd = add, confirmation = order(100, actor = "did:a", event = "confirm"), count = 1),
        )
        assertBasis(
            MapSituationConfirmationFreshnessBasis.Current(100),
            entity(canonicalAdd = add, confirmation = order(100, actor = "did:c", event = "confirm"), count = 1),
        )
    }

    @Test fun activeSituationChangeAfterConfirmationInvalidatesEvidence() {
        assertBasis(
            MapSituationConfirmationFreshnessBasis.InvalidatedBySituationChange,
            entity(confirmation = order(20), update = order(30), material = order(30), count = 0),
        )
    }

    @Test fun activeConfirmationAfterSituationChangeIsCurrent() {
        assertBasis(
            MapSituationConfirmationFreshnessBasis.Current(30),
            entity(confirmation = order(30), update = order(20), material = order(20), count = 3),
        )
    }

    @Test fun canonicalActorAndEventTieBreakersDetermineFreshness() {
        val change = order(20, actor = "did:b", event = "change")
        assertBasis(
            MapSituationConfirmationFreshnessBasis.InvalidatedBySituationChange,
            entity(confirmation = order(20, actor = "did:a", event = "confirm"), update = change,
                material = change, count = 0),
        )
        assertBasis(
            MapSituationConfirmationFreshnessBasis.Current(20),
            entity(confirmation = order(20, actor = "did:c", event = "confirm"), update = change,
                material = change, count = 1),
        )
    }

    @Test fun resolvedConfirmationIsInvalidatedByResolution() {
        val resolution = order(40, event = "resolved")
        assertBasis(
            MapSituationConfirmationFreshnessBasis.InvalidatedByResolution,
            entity(lifecycle = "no_longer_active", confirmation = order(20), lifecycleOrder = resolution,
                material = resolution, count = 0),
        )
    }

    @Test fun resolvedExplicitStateWithoutConfirmationProvesNoPriorConfirmation() {
        val resolution = order(40, event = "resolved")
        assertBasis(
            MapSituationConfirmationFreshnessBasis.NoAcceptedPriorConfirmationEstablished,
            entity(lifecycle = "no_longer_active", lifecycleOrder = resolution, material = resolution),
        )
    }

    @Test fun legacyAbsenceRemainsUnknown() {
        assertBasis(MapSituationConfirmationFreshnessBasis.Unknown, entity(stateOrigin = "legacy_row_backfill"))
    }

    @Test fun impossibleOrContradictoryMetadataFailsSafeToUnknown() {
        val same = order(20)
        listOf(
            entity(confirmation = null, count = 1),
            entity(confirmation = same, update = same, material = same, count = 0),
            entity(confirmation = order(30), update = order(20), material = order(20), count = 0),
            entity(lifecycle = "no_longer_active", confirmation = order(50), lifecycleOrder = order(40),
                material = order(40), count = 0),
            entity(lifecycle = "no_longer_active", lifecycleOrder = order(40), material = order(39)),
        ).forEach { assertBasis(MapSituationConfirmationFreshnessBasis.Unknown, it) }
    }

    private fun assertBasis(expected: MapSituationConfirmationFreshnessBasis, entity: MapItemDomainStateEntity) {
        val result = MapItemEntityMappers.domain(entity) as PersistenceParseResult.Success
        assertEquals(expected, result.value.confirmationFreshnessBasis)
    }

    private fun entity(
        stateOrigin: String = "explicit_domain_v1",
        lifecycle: String = "active",
        canonicalAdd: StoredOrder? = order(10, actor = "did:owner", event = "add"),
        confirmation: StoredOrder? = null,
        update: StoredOrder? = null,
        lifecycleOrder: StoredOrder? = null,
        material: StoredOrder? = null,
        count: Int = 0,
    ) = MapItemDomainStateEntity(
        gid = "g", item_type = "marker", item_id = "i", category = "situation",
        category_origin = if (stateOrigin == "explicit_domain_v1") "explicit_domain_v1" else "legacy_kind_derived",
        creator_information_source = if (stateOrigin == "explicit_domain_v1") "firsthand" else null,
        domain_version = if (stateOrigin == "explicit_domain_v1") 1 else null,
        state_origin = stateOrigin, owner_did = "did:owner",
        canonical_add_t = canonicalAdd?.timestamp, canonical_add_lt = canonicalAdd?.lamport,
        canonical_add_actor = canonicalAdd?.actor, canonical_add_event_id = canonicalAdd?.event,
        situation_lifecycle = lifecycle, expires_at = null, owner_edit_until = null,
        interaction_until = null, temporal_origin = if (stateOrigin == "explicit_domain_v1") {
            "explicit_domain_v1"
        } else {
            "legacy_no_expiry"
        },
        confirmation_actor_count = count, acknowledgement_actor_count = 0,
        latest_confirmation_event_id = confirmation?.event, latest_confirmation_t = confirmation?.timestamp,
        latest_confirmation_lt = confirmation?.lamport, latest_confirmation_actor = confirmation?.actor,
        latest_update_event_id = update?.event, latest_update_t = update?.timestamp,
        latest_update_lt = update?.lamport, latest_update_actor = update?.actor,
        latest_lifecycle_event_id = lifecycleOrder?.event, latest_lifecycle_t = lifecycleOrder?.timestamp,
        latest_lifecycle_lt = lifecycleOrder?.lamport, latest_lifecycle_actor = lifecycleOrder?.actor,
        latest_material_event_id = material?.event, latest_material_t = material?.timestamp,
        latest_material_lt = material?.lamport, latest_material_actor = material?.actor,
    )

    private fun order(
        timestamp: Long,
        lamport: Long = 0,
        actor: String = "did:a",
        event: String = "confirm",
    ) = StoredOrder(timestamp, lamport, actor, event)

    private data class StoredOrder(val timestamp: Long, val lamport: Long, val actor: String, val event: String)
}
