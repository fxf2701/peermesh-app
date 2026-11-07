package app.peermesh.data.db

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemAction
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.core.map.MapSituationUpdateType
import app.peermesh.data.db.entity.MapItemActionEntity
import app.peermesh.data.db.entity.MapItemDomainStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapItemPersistenceContractsTest {
    @Test fun `all explicit values round trip independently of enum names`() {
        MapItemType.entries.forEach { assertRoundTrip(it, MapItemPersistenceValues.ItemType.valueOf(it), MapItemPersistenceValues.ItemType::parse) }
        MapItemCategory.entries.forEach { assertRoundTrip(it, MapItemPersistenceValues.Category.valueOf(it), MapItemPersistenceValues.Category::parse) }
        MapInformationSource.entries.forEach { assertNullableRoundTrip(it, MapItemPersistenceValues.InformationSource.valueOf(it), MapItemPersistenceValues.InformationSource::parseNullable) }
        MapItemAction.entries.forEach { assertRoundTrip(it, MapItemPersistenceValues.ActionType.valueOf(it), MapItemPersistenceValues.ActionType::parse) }
        MapSituationLifecycleStatus.entries.forEach { assertNullableRoundTrip(it, MapItemPersistenceValues.SituationLifecycle.valueOf(it), MapItemPersistenceValues.SituationLifecycle::parseNullable) }
        MapSituationUpdateType.entries.forEach { assertNullableRoundTrip(it, MapItemPersistenceValues.UpdateDisposition.valueOf(it), MapItemPersistenceValues.UpdateDisposition::parseNullable) }
        MapItemCategoryOrigin.entries.forEach { assertRoundTrip(it, it.persistedValue, MapItemPersistenceValues.CategoryOrigin::parse) }
        MapItemStateOrigin.entries.forEach { assertRoundTrip(it, it.persistedValue, MapItemPersistenceValues.StateOrigin::parse) }
        MapItemTemporalOrigin.entries.forEach { assertRoundTrip(it, it.persistedValue, MapItemPersistenceValues.TemporalOrigin::parse) }
        MapItemMaterializationState.entries.forEach { assertRoundTrip(it, it.persistedValue, MapItemPersistenceValues.MaterializationState::parse) }
        assertNotEquals(MapItemType.MARKER.name, MapItemPersistenceValues.ItemType.valueOf(MapItemType.MARKER))
        assertNotEquals(MapItemMaterializationState.PENDING_TARGET.name, MapItemMaterializationState.PENDING_TARGET.persistedValue)
    }

    @Test fun `strict parsing distinguishes missing unknown nullable and unresolved`() {
        assertTrue(MapItemPersistenceValues.ItemType.parse(null) is PersistenceParseResult.MissingRequiredValue)
        assertTrue(MapItemPersistenceValues.ItemType.parse("future") is PersistenceParseResult.UnknownPersistedValue)
        assertEquals(PersistenceParseResult.Success(null), MapItemPersistenceValues.Category.parseNullable(null))
        assertTrue(MapItemPersistenceValues.Category.parseNullable("future") is PersistenceParseResult.UnknownPersistedValue)
        assertEquals(PersistenceParseResult.Success(MapItemCategoryOrigin.UNRESOLVED),
            MapItemPersistenceValues.CategoryOrigin.parse("unresolved"))
        assertSuccess(MapItemPersistenceRecordValidator.validate(domain()))
        assertTrue(MapItemPersistenceRecordValidator.validate(domain(category = "future")) is PersistenceParseResult.UnknownPersistedValue)
    }

    @Test fun `action record validates identity time lamport and rejection shape`() {
        assertSuccess(MapItemPersistenceRecordValidator.validate(action()))
        assertInvalid(MapItemPersistenceRecordValidator.validate(action(action_id = "different", event_id = "e")))
        assertInvalid(MapItemPersistenceRecordValidator.validate(action(action_t = 0)))
        assertInvalid(MapItemPersistenceRecordValidator.validate(action(action_lt = -1)))
        assertInvalid(MapItemPersistenceRecordValidator.validate(action(actor_did = "")))
        assertInvalid(MapItemPersistenceRecordValidator.validate(action(materialization_state = "rejected")))
        assertSuccess(MapItemPersistenceRecordValidator.validate(action(materialization_state = "rejected", rejection_reason = "unknown_target")))
        assertInvalid(MapItemPersistenceRecordValidator.validate(action(rejection_reason = "unexpected")))
    }

    @Test fun `versions and rejection reasons are strict`() {
        assertSuccess(MapItemPersistenceRecordValidator.validate(action(map_domain_v = 1)))
        assertInvalid(MapItemPersistenceRecordValidator.validate(action(map_domain_v = 0)))
        assertInvalid(MapItemPersistenceRecordValidator.validate(action(map_domain_v = 2)))
        assertSuccess(MapItemPersistenceRecordValidator.validate(domain(domain_version = null)))
        assertSuccess(MapItemPersistenceRecordValidator.validate(domain(domain_version = 1)))
        assertInvalid(MapItemPersistenceRecordValidator.validate(domain(domain_version = 0)))
        assertInvalid(MapItemPersistenceRecordValidator.validate(domain(domain_version = 2)))
        assertInvalid(MapItemPersistenceRecordValidator.validate(domain(
            state_origin = "explicit_domain_v1", domain_version = null,
        )))
        assertInvalid(MapItemPersistenceRecordValidator.validate(domain(
            state_origin = "explicit_domain_v1", domain_version = 1,
            category_origin = "unresolved", category = null,
        )))
        assertInvalid(MapItemPersistenceRecordValidator.validate(domain(
            state_origin = "explicit_domain_v1", domain_version = 1,
            category_origin = "legacy_kind_derived", category = "situation",
        )))
        assertInvalid(MapItemPersistenceRecordValidator.validate(domain(
            state_origin = "explicit_domain_v1", domain_version = 1,
            category_origin = "explicit_domain_v1", category = null,
        )))
        assertSuccess(MapItemPersistenceRecordValidator.validate(domain(
            state_origin = "explicit_domain_v1", domain_version = 1,
            category_origin = "explicit_domain_v1", category = "situation",
        )))
        assertSuccess(MapItemPersistenceRecordValidator.validate(domain(
            state_origin = "legacy_row_backfill", domain_version = null,
            category_origin = "unresolved", category = null,
        )))
        assertSuccess(MapItemPersistenceRecordValidator.validate(domain(
            state_origin = "legacy_row_backfill", domain_version = null,
            category_origin = "legacy_kind_derived", category = "situation",
        )))
        assertSuccess(MapItemRejectionReasonContract.validate("unknown_target"))
        listOf("", "Localized reason", "Unknown_Target", "unknown-target").forEach {
            assertInvalid(MapItemRejectionReasonContract.validate(it))
        }
    }

    @Test fun `typed mutation validation rejects invalid order identity and counts`() {
        assertSuccess(MapItemPersistenceMutationValidator.order(MapActionOrderKey(1, 0, "did:a", "e")))
        assertInvalid(MapItemPersistenceMutationValidator.order(MapActionOrderKey(0, 0, "did:a", "e")))
        assertInvalid(MapItemPersistenceMutationValidator.order(MapActionOrderKey(1, -1, "did:a", "e")))
        assertInvalid(MapItemPersistenceMutationValidator.order(MapActionOrderKey(1, 0, "", "e")))
        assertInvalid(MapItemPersistenceMutationValidator.order(MapActionOrderKey(1, 0, "did:a", "")))
        assertInvalid(MapItemPersistenceMutationValidator.targetIdentity("", MapItemType.MARKER, "i"))
        assertInvalid(MapItemPersistenceMutationValidator.counts(-1, 0))
    }

    @Test fun `domain record validates counts temporal windows and complete order tuples`() {
        assertInvalid(MapItemPersistenceRecordValidator.validate(domain(confirmation_actor_count = -1)))
        assertInvalid(MapItemPersistenceRecordValidator.validate(domain(expires_at = 0)))
        assertInvalid(MapItemPersistenceRecordValidator.validate(domain(expires_at = null, interaction_until = 20)))
        assertInvalid(MapItemPersistenceRecordValidator.validate(domain(expires_at = 20, interaction_until = 19)))
        assertSuccess(MapItemPersistenceRecordValidator.validate(domain(expires_at = 20, owner_edit_until = 30, interaction_until = 20)))
        assertSuccess(MapItemPersistenceRecordValidator.validate(domain(expires_at = 20, owner_edit_until = 10, interaction_until = 25)))
        assertSuccess(MapItemPersistenceRecordValidator.validate(domain()))
        assertInvalid(MapItemPersistenceRecordValidator.validate(domain(canonical_add_t = 10)))
        assertSuccess(MapItemPersistenceRecordValidator.validate(domain(
            canonical_add_t = 10, canonical_add_lt = 0, canonical_add_actor = "did:a", canonical_add_event_id = "e",
        )))
        assertInvalid(MapItemPersistenceRecordValidator.validate(domain(category = null, category_origin = "legacy_kind_derived")))
    }

    @Test fun `order comparator covers every tie and reverses the complete tuple`() {
        val keys = listOf(
            MapActionOrderKey(2, 0, "a", "a"),
            MapActionOrderKey(1, 2, "a", "a"),
            MapActionOrderKey(1, 1, "b", "a"),
            MapActionOrderKey(1, 1, "a", "b"),
            MapActionOrderKey(1, 1, "a", "a"),
        )
        val ascending = keys.sortedWith(MapActionOrderKey.ASCENDING)
        assertEquals(listOf("1/1/a/a", "1/1/a/b", "1/1/b/a", "1/2/a/a", "2/0/a/a"), ascending.map(::render))
        assertEquals(ascending.reversed(), keys.sortedWith(MapActionOrderKey.DESCENDING))
        assertEquals(0, MapActionOrderKey.ASCENDING.compare(ascending[0], ascending[0].copy()))
        assertTrue(MapActionOrderKey.ASCENDING.compare(MapActionOrderKey(1, 1, "a", "a"), MapActionOrderKey(2, 0, "a", "a")) < 0)
        assertTrue(MapActionOrderKey.ASCENDING.compare(MapActionOrderKey(1, 1, "a", "a"), MapActionOrderKey(1, 2, "a", "a")) < 0)
        assertTrue(MapActionOrderKey.ASCENDING.compare(MapActionOrderKey(1, 1, "a", "a"), MapActionOrderKey(1, 1, "b", "a")) < 0)
        assertTrue(MapActionOrderKey.ASCENDING.compare(MapActionOrderKey(1, 1, "a", "a"), MapActionOrderKey(1, 1, "a", "b")) < 0)
    }

    private fun render(key: MapActionOrderKey) = "${key.timestampSeconds}/${key.lamport}/${key.actorDid}/${key.eventId}"
    private fun <T> assertRoundTrip(expected: T, value: String, parser: (String?) -> PersistenceParseResult<T>) =
        assertEquals(PersistenceParseResult.Success(expected), parser(value))
    private fun <T> assertNullableRoundTrip(expected: T, value: String, parser: (String?) -> PersistenceParseResult<T?>) =
        assertEquals(PersistenceParseResult.Success(expected), parser(value))
    private fun assertSuccess(result: PersistenceParseResult<*>) = assertTrue(result is PersistenceParseResult.Success)
    private fun assertInvalid(result: PersistenceParseResult<*>) = assertTrue(result is PersistenceParseResult.InvalidRecordShape)

    private fun action(
        gid: String = "g", action_id: String = "e", event_id: String = action_id,
        actor_did: String = "did:a", action_t: Long = 10, action_lt: Long = 0,
        materialization_state: String = "pending_target", rejection_reason: String? = null, map_domain_v: Int = 1,
    ) = MapItemActionEntity(gid, action_id, event_id, "MAP.ITEM.CONFIRM", map_domain_v, "marker", "i", "confirm",
        actor_did, action_t, action_lt, null, null, null, null, "sha256", materialization_state, rejection_reason)

    private fun domain(
        category: String? = null, category_origin: String = "unresolved", confirmation_actor_count: Int = 0,
        expires_at: Long? = null, owner_edit_until: Long? = null, interaction_until: Long? = null,
        canonical_add_event_id: String? = null, canonical_add_t: Long? = null,
        canonical_add_lt: Long? = null, canonical_add_actor: String? = null,
        domain_version: Int? = null, state_origin: String = "legacy_row_backfill",
    ) = MapItemDomainStateEntity("g", "marker", "i", category, category_origin, null, domain_version,
        state_origin, "did:o", canonical_add_event_id, canonical_add_t, canonical_add_lt,
        canonical_add_actor, null, expires_at, owner_edit_until, interaction_until, "legacy_no_expiry",
        confirmation_actor_count, 0, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null)
}
