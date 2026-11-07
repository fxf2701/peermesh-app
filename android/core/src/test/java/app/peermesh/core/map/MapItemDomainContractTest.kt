package app.peermesh.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MapItemDomainContractTest {
    private val metadata = MapActionMetadata(
        actionId = "event-1",
        itemId = "item-1",
        actorDid = "actor-1",
        timestampSeconds = 100,
        eventLamport = 200,
    )

    @Test
    fun `every canonical kind has exactly one corrected category`() {
        assertEquals(26, MapItemKindCatalog.canonicalKinds.size)
        MapItemKindCatalog.canonicalKinds.forEach { kind ->
            assertNotNull("Missing category for $kind", MapItemKindCatalog.categoryFor(kind))
        }
        assertEquals(MapItemCategory.SITUATION, MapItemKindCatalog.categoryFor("resource"))
        assertEquals(MapItemCategory.SITUATION, MapItemKindCatalog.categoryFor("connectivity"))
        assertEquals(MapItemCategory.SITUATION, MapItemKindCatalog.categoryFor("checkpoint"))
        assertEquals(MapItemCategory.COORDINATION, MapItemKindCatalog.categoryFor("camp"))
        assertEquals(MapItemCategory.COORDINATION, MapItemKindCatalog.categoryFor("waypoint"))
        assertEquals(MapItemCategory.COORDINATION, MapItemKindCatalog.categoryFor("meeting_point"))
        assertEquals(MapItemCategory.COORDINATION, MapItemKindCatalog.categoryFor("assembly_zone"))
        assertEquals(MapItemCategory.COORDINATION, MapItemKindCatalog.categoryFor("trail_sector"))
        assertEquals(MapItemCategory.COORDINATION, MapItemKindCatalog.categoryFor("event_zone"))
        assertEquals(MapItemCategory.COORDINATION, MapItemKindCatalog.categoryFor("rescue_staging"))
        assertTrue(MapItemKindCatalog.supportsArea("assembly_zone"))
        assertTrue(MapItemKindCatalog.supportsArea("trail_sector"))
        assertTrue(MapItemKindCatalog.supportsArea("event_zone"))
        assertTrue(MapItemKindCatalog.supportsArea("rescue_staging"))
        assertFalse(MapItemKindCatalog.supportsArea("meeting_point"))
        assertNull(MapItemKindCatalog.categoryFor("future_kind_without_category"))
    }

    @Test
    fun `legacy resource aliases normalize to Situation without inventing source`() {
        assertEquals("resource", MapItemKindCatalog.normalize("food_water"))
        assertEquals("resource", MapItemKindCatalog.normalize("shelter"))
        assertEquals(MapItemCategory.SITUATION, MapItemKindCatalog.categoryFor("food_water"))
        assertEquals(MapItemCategory.SITUATION, MapItemKindCatalog.categoryFor("shelter"))
        LegacyMapConfidence.entries.forEach { confidence ->
            assertEquals(
                LegacyMapSourcePresentation.UNSPECIFIED_LEGACY_SOURCE,
                MapItemCompatibilityPolicy.legacySourcePresentation(confidence),
            )
        }
    }

    @Test
    fun `action and source wire values round trip and unknown values are rejected`() {
        MapItemAction.entries.forEach { value ->
            assertEquals(value, MapItemAction.fromWireValue(value.wireValue))
        }
        MapInformationSource.entries.forEach { value ->
            assertEquals(value, MapInformationSource.fromWireValue(value.wireValue))
        }
        MapSituationLifecycleStatus.entries.forEach { value ->
            assertEquals(value, MapSituationLifecycleStatus.fromWireValue(value.wireValue))
        }
        MapSituationUpdateType.entries.forEach { value ->
            assertEquals(value, MapSituationUpdateType.fromWireValue(value.wireValue))
        }
        assertNull(MapItemAction.fromWireValue("future_action"))
        assertNull(MapInformationSource.fromWireValue("observed"))
        assertNull(MapSituationLifecycleStatus.fromWireValue("situation_changed"))
        assertNull(MapSituationUpdateType.fromWireValue("no_longer_active"))
    }

    @Test
    fun `lifecycle and update disposition remain separate`() {
        val update = MapSituationUpdate(
            metadata = metadata,
            content = "Water level moved north",
            source = MapInformationSource.FIRSTHAND,
            updateType = MapSituationUpdateType.SITUATION_CHANGED,
        )
        assertEquals(MapSituationUpdateType.SITUATION_CHANGED, update.updateType)
        assertTrue(
            MapSituationLifecyclePolicy.canTransition(
                MapSituationLifecycleStatus.ACTIVE,
                MapSituationLifecycleStatus.ACTIVE,
            )
        )
        assertTrue(
            MapSituationLifecyclePolicy.canTransition(
                MapSituationLifecycleStatus.ACTIVE,
                MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
            )
        )
        assertTrue(
            MapSituationLifecyclePolicy.canTransition(
                MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
                MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
            )
        )
        assertFalse(
            MapSituationLifecyclePolicy.canTransition(
                MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
                MapSituationLifecycleStatus.ACTIVE,
            )
        )
    }

    @Test
    fun `user status action only accepts No longer active target`() {
        assertFalse(MapSituationStatusActionPolicy.isValidTarget(MapSituationLifecycleStatus.ACTIVE))
        assertTrue(MapSituationStatusActionPolicy.isValidTarget(MapSituationLifecycleStatus.NO_LONGER_ACTIVE))
    }

    @Test
    fun `every action record carries stable action metadata`() {
        val records = listOf(
            MapConfirmation(metadata, null, null),
            MapSituationUpdate(metadata, "Changed", null, MapSituationUpdateType.SITUATION_CHANGED),
            MapSituationStatusUpdate(metadata, MapSituationLifecycleStatus.NO_LONGER_ACTIVE, null, null),
            MapCoordinationAcknowledgement(metadata),
            MapCoordinationNote(metadata, "Bring water"),
        )
        records.forEach { record ->
            val recordMetadata = when (record) {
                is MapConfirmation -> record.metadata
                is MapSituationUpdate -> record.metadata
                is MapSituationStatusUpdate -> record.metadata
                is MapCoordinationAcknowledgement -> record.metadata
                is MapCoordinationNote -> record.metadata
                else -> error("Unexpected record")
            }
            assertSame(metadata, recordMetadata)
            assertEquals("event-1", recordMetadata.actionId)
        }
    }

    @Test
    fun `Situation and Coordination action sets do not overlap`() {
        assertEquals(
            setOf(MapItemAction.CONFIRM, MapItemAction.ADD_UPDATE, MapItemAction.UPDATE_STATUS),
            MapItemActionPolicy.actionsFor(MapItemCategory.SITUATION),
        )
        assertEquals(
            setOf(MapItemAction.ACKNOWLEDGE, MapItemAction.ADD_NOTE),
            MapItemActionPolicy.actionsFor(MapItemCategory.COORDINATION),
        )
        MapItemAction.entries.forEach { action ->
            assertTrue(
                MapItemActionPolicy.allows(MapItemCategory.SITUATION, action) xor
                    MapItemActionPolicy.allows(MapItemCategory.COORDINATION, action)
            )
        }
    }

    @Test
    fun `source requirements cover every valid and cross-category operation`() {
        MapItemOperation.entries.forEach { operation ->
            val situationRequirement = MapInformationSourcePolicy.requirementFor(
                MapItemCategory.SITUATION,
                operation,
            )
            val coordinationRequirement = MapInformationSourcePolicy.requirementFor(
                MapItemCategory.COORDINATION,
                operation,
            )
            when (operation) {
                MapItemOperation.CREATE -> {
                    assertEquals(MapSourceRequirement.REQUIRED, situationRequirement)
                    assertEquals(MapSourceRequirement.FORBIDDEN, coordinationRequirement)
                }
                MapItemOperation.CONFIRM,
                MapItemOperation.ADD_UPDATE,
                MapItemOperation.UPDATE_STATUS,
                -> {
                    assertEquals(MapSourceRequirement.OPTIONAL, situationRequirement)
                    assertNull(coordinationRequirement)
                }
                MapItemOperation.ACKNOWLEDGE,
                MapItemOperation.ADD_NOTE,
                -> {
                    assertNull(situationRequirement)
                    assertEquals(MapSourceRequirement.FORBIDDEN, coordinationRequirement)
                }
            }
        }

        assertFalse(MapInformationSourcePolicy.isValid(MapItemCategory.SITUATION, MapItemOperation.CREATE, null))
        assertTrue(
            MapInformationSourcePolicy.isValid(
                MapItemCategory.SITUATION,
                MapItemOperation.CREATE,
                MapInformationSource.UNCERTAIN,
            )
        )
        assertTrue(MapInformationSourcePolicy.isValid(MapItemCategory.SITUATION, MapItemOperation.CONFIRM, null))
        assertTrue(MapInformationSourcePolicy.isValid(MapItemCategory.SITUATION, MapItemOperation.ADD_UPDATE, null))
        assertTrue(MapInformationSourcePolicy.isValid(MapItemCategory.SITUATION, MapItemOperation.UPDATE_STATUS, null))
        assertTrue(MapInformationSourcePolicy.isValid(MapItemCategory.COORDINATION, MapItemOperation.CREATE, null))
        assertFalse(
            MapInformationSourcePolicy.isValid(
                MapItemCategory.COORDINATION,
                MapItemOperation.CREATE,
                MapInformationSource.FIRSTHAND,
            )
        )
        assertTrue(MapInformationSourcePolicy.isValid(MapItemCategory.COORDINATION, MapItemOperation.ACKNOWLEDGE, null))
        assertTrue(MapInformationSourcePolicy.isValid(MapItemCategory.COORDINATION, MapItemOperation.ADD_NOTE, null))
        assertFalse(MapInformationSourcePolicy.isValid(MapItemCategory.COORDINATION, MapItemOperation.CONFIRM, null))
        assertFalse(MapInformationSourcePolicy.isValid(MapItemCategory.SITUATION, MapItemOperation.ACKNOWLEDGE, null))
    }

    @Test
    fun `structural invariants reject blank identity and content without implying full validation`() {
        assertTrue(MapActionInvariantPolicy.isMetadataValid(metadata))
        assertFalse(MapActionInvariantPolicy.isMetadataValid(metadata.copy(actionId = " ")))
        assertFalse(MapActionInvariantPolicy.isMetadataValid(metadata.copy(itemId = "")))
        assertFalse(MapActionInvariantPolicy.isMetadataValid(metadata.copy(actorDid = "")))
        assertFalse(MapActionInvariantPolicy.isMetadataValid(metadata.copy(timestampSeconds = 0)))
        assertFalse(MapActionInvariantPolicy.isMetadataValid(metadata.copy(eventLamport = -1)))
        assertFalse(
            MapActionInvariantPolicy.isStructurallyValid(
                MapSituationUpdate(metadata, " ", null, MapSituationUpdateType.STILL_ACTIVE)
            )
        )
        assertFalse(MapActionInvariantPolicy.isStructurallyValid(MapCoordinationNote(metadata, "")))
        assertTrue(
            MapActionInvariantPolicy.isStructurallyValid(
                MapSituationStatusUpdate(
                    metadata,
                    MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
                    note = null,
                    source = null,
                )
            )
        )
    }

    @Test
    fun `temporal windows preserve active interaction and enforce boundaries`() {
        val windows = MapItemWindows(
            expiresAtSeconds = 1_000,
            ownerEditUntilSeconds = 200,
            interactionUntilSeconds = 1_000,
        )
        assertTrue(MapItemTemporalPolicy.areWindowsValid(windows))
        assertFalse(
            MapItemTemporalPolicy.canOwnerEditSituation(
                windows,
                MapSituationLifecycleStatus.ACTIVE,
                300,
            )
        )
        assertTrue(
            MapItemTemporalPolicy.canInteractWithSituation(
                windows,
                MapSituationLifecycleStatus.ACTIVE,
                999,
            )
        )
        assertFalse(
            MapItemTemporalPolicy.canInteractWithSituation(
                windows,
                MapSituationLifecycleStatus.ACTIVE,
                1_000,
            )
        )
        assertFalse(
            MapItemTemporalPolicy.canInteractWithSituation(
                windows,
                MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
                300,
            )
        )
        assertFalse(MapItemTemporalPolicy.areWindowsValid(windows.copy(interactionUntilSeconds = 999)))

        val laterInteraction = windows.copy(interactionUntilSeconds = 1_200)
        assertTrue(MapItemTemporalPolicy.areWindowsValid(laterInteraction))
        assertFalse(MapItemTemporalPolicy.canInteractWithCoordination(laterInteraction, 1_000))

        val indefinite = MapItemWindows(null, ownerEditUntilSeconds = 500, interactionUntilSeconds = null)
        assertTrue(MapItemTemporalPolicy.areWindowsValid(indefinite))
        assertTrue(MapItemTemporalPolicy.canInteractWithCoordination(indefinite, 10_000))
        assertFalse(MapItemTemporalPolicy.areWindowsValid(indefinite.copy(interactionUntilSeconds = 2_000)))
        assertFalse(MapItemTemporalPolicy.areWindowsValid(windows.copy(expiresAtSeconds = -1)))
        assertFalse(MapItemTemporalPolicy.areWindowsValid(windows.copy(ownerEditUntilSeconds = 0)))
        assertFalse(MapItemTemporalPolicy.areWindowsValid(windows.copy(interactionUntilSeconds = -5)))

        val ownerEditAfterExpiry = windows.copy(ownerEditUntilSeconds = 1_200)
        assertTrue(MapItemTemporalPolicy.areWindowsValid(ownerEditAfterExpiry))
        assertFalse(MapItemTemporalPolicy.canOwnerEditCoordination(ownerEditAfterExpiry, 1_000))
    }

    @Test
    fun `payload action note field is the single reserved action content field`() {
        assertEquals("action_note", MapDomainPayloadContract.ACTION_NOTE_FIELD)
        assertEquals("situation_lifecycle", MapDomainPayloadContract.LIFECYCLE_STATUS_FIELD)
        assertEquals("situation_update_type", MapDomainPayloadContract.UPDATE_TYPE_FIELD)
    }

    @Test
    fun `verification uses distinct actor count input and rejects negative count`() {
        assertEquals(
            MapCommunityVerification.UNVERIFIED,
            MapCommunityVerificationPolicy.derive(MapCommunityVerificationInputs(0, false)),
        )
        assertEquals(
            MapCommunityVerification.PARTIALLY_VERIFIED,
            MapCommunityVerificationPolicy.derive(MapCommunityVerificationInputs(1, false)),
        )
        assertEquals(
            MapCommunityVerification.CONFIRMED,
            MapCommunityVerificationPolicy.derive(MapCommunityVerificationInputs(2, false)),
        )
        assertEquals(
            MapCommunityVerification.CONFLICTING_UPDATES,
            MapCommunityVerificationPolicy.derive(MapCommunityVerificationInputs(5, true)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            MapCommunityVerificationInputs(-1, false)
        }
    }
}
