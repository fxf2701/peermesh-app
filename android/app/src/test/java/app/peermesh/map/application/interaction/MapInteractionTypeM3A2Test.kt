package app.peermesh.map.application.interaction

import app.peermesh.core.map.MapItemAction
import app.peermesh.core.map.MapItemActionPolicy
import app.peermesh.core.map.MapItemCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapInteractionTypeM3A2Test {
    @Test fun supportedInteractionFamiliesAreStableSeparatedAndMappedToM2() {
        assertEquals(5, MapInteractionType.all.size)
        assertEquals(5, MapInteractionType.all.toSet().size)
        assertEquals(3, MapInteractionType.all.count { it.family == MapInteractionFamily.SITUATION })
        assertEquals(2, MapInteractionType.all.count { it.family == MapInteractionFamily.COORDINATION })
        assertEquals(
            listOf(MapItemAction.CONFIRM, MapItemAction.ADD_UPDATE, MapItemAction.UPDATE_STATUS,
                MapItemAction.ACKNOWLEDGE, MapItemAction.ADD_NOTE),
            MapInteractionType.all.map { it.m2Action },
        )
    }

    @Test fun applicationVocabularyHasNoWireOrMarkerAreaSpecificTypes() {
        MapInteractionType.all.forEach {
            assertFalse(it.toString().contains("MAP.ITEM"))
            assertFalse(it::class.java.simpleName.contains("Marker"))
            assertFalse(it::class.java.simpleName.contains("Area"))
        }
        assertTrue(MapInteractionType.all.none { it.toString().contains("REACT", ignoreCase = true) })
    }

    @Test fun interactionFamiliesStayAlignedWithAcceptedM2Policy() {
        MapInteractionType.all.forEach { interaction ->
            assertEquals(interaction.family == MapInteractionFamily.SITUATION,
                MapItemActionPolicy.allows(MapItemCategory.SITUATION, interaction.m2Action))
            assertEquals(interaction.family == MapInteractionFamily.COORDINATION,
                MapItemActionPolicy.allows(MapItemCategory.COORDINATION, interaction.m2Action))
        }
    }

    @Test fun productionDefaultsSupportAllLocalActionsAndKeepPeerCapabilityUnknown() {
        assertEquals(MapInteractionType.all.toSet(), LocalMapInteractionSupport.allSupported().supportedInteractions)
        assertEquals(MapPeerCapabilityLevel.UNKNOWN,
            UnknownMapPeerCapabilityProvider.capabilityFor("g").level)
    }

    @Test fun publicAvailabilityContractsExposeNoRoomEntityOrEventConstructionType() {
        val contracts = listOf(
            MapInteractionType::class.java,
            MapItemInteractionAvailability::class.java,
            MapItemInteractionsAvailability::class.java,
            MapItemInteractionEvaluationInput::class.java,
            LocalMapParticipation::class.java,
            MapPeerCapabilitySummary::class.java,
        )
        contracts.flatMap { it.declaredFields.asList() }.forEach { field ->
            assertFalse(field.type.name.contains(".data.db.entity."))
            assertFalse(field.type.name.contains("EventCore"))
        }
    }
}
