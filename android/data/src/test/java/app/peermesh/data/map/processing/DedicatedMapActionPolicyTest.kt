package app.peermesh.data.map.processing

import app.peermesh.core.map.*
import app.peermesh.data.db.*
import app.peermesh.data.map.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DedicatedMapActionPolicyTest {
    @Test fun categoryMatrixAcceptsOnlyItsActionFamily() {
        listOf(confirm(), update(), status()).forEach { assertAccepted(context(it, MapItemCategory.SITUATION)) }
        listOf(ack(), note()).forEach { assertAccepted(context(it, MapItemCategory.COORDINATION)) }
        listOf(ack(), note()).forEach { assertRejected(context(it, MapItemCategory.SITUATION), MapEventRejectionReason.ACTION_NOT_ALLOWED_FOR_CATEGORY) }
        listOf(confirm(), update(), status()).forEach { assertRejected(context(it, MapItemCategory.COORDINATION), MapEventRejectionReason.ACTION_NOT_ALLOWED_FOR_CATEGORY) }
        assertRejected(context(confirm(), null), MapEventRejectionReason.TARGET_CATEGORY_UNRESOLVED)
    }

    @Test fun temporalBoundariesAreExclusiveExpiryFirstAndOwnerEditIsIgnored() {
        assertRejected(context(confirm(20), windows = app.peermesh.data.map.MapItemWindows(20, 1, 30)), MapEventRejectionReason.TARGET_EXPIRED)
        assertRejected(context(confirm(20), windows = app.peermesh.data.map.MapItemWindows(30, 1, 20)), MapEventRejectionReason.INTERACTION_WINDOW_CLOSED)
        assertAccepted(context(confirm(20), windows = app.peermesh.data.map.MapItemWindows(30, 1, 21)))
    }

    @Test fun lifecycleOrderingUsesCompleteComparatorAndRepeatedResolutionIsAccepted() {
        val resolution = MapActionOrderKey(20, 2, "m", "resolution")
        assertTrue(DefaultDedicatedMapActionPolicy.evaluate(context(confirm(19), lifecycleOrder = resolution)) is DedicatedMapActionPolicyDecision.AcceptHistorical)
        assertRejected(context(confirm(21), lifecycleOrder = resolution), MapEventRejectionReason.TARGET_RESOLVED)
        assertTrue(DefaultDedicatedMapActionPolicy.evaluate(context(confirm(20, 1, "z", "z"), lifecycleOrder = resolution)) is DedicatedMapActionPolicyDecision.AcceptHistorical)
        assertRejected(context(confirm(20, 3, "a", "a"), lifecycleOrder = resolution), MapEventRejectionReason.TARGET_RESOLVED)
        assertTrue(DefaultDedicatedMapActionPolicy.evaluate(context(confirm(20, 2, "a", "z"), lifecycleOrder = resolution)) is DedicatedMapActionPolicyDecision.AcceptHistorical)
        assertRejected(context(confirm(20, 2, "z", "a"), lifecycleOrder = resolution), MapEventRejectionReason.TARGET_RESOLVED)
        assertAccepted(context(status(21), lifecycleOrder = resolution, lifecycle = MapSituationLifecycleStatus.NO_LONGER_ACTIVE))
    }

    private fun assertAccepted(c: DedicatedMapActionContext) = assertTrue(DefaultDedicatedMapActionPolicy.evaluate(c) is DedicatedMapActionPolicyDecision.AcceptApplied)
    private fun assertRejected(c: DedicatedMapActionContext, reason: MapEventRejectionReason) =
        assertEquals(reason, (DefaultDedicatedMapActionPolicy.evaluate(c) as DedicatedMapActionPolicyDecision.Reject).reason)

    private fun context(
        interaction: MapItemInteractionEvent,
        category: MapItemCategory? = MapItemCategory.SITUATION,
        windows: app.peermesh.data.map.MapItemWindows = app.peermesh.data.map.MapItemWindows(null, null, null),
        lifecycleOrder: MapActionOrderKey? = null,
        lifecycle: MapSituationLifecycleStatus? = if (category == MapItemCategory.SITUATION) MapSituationLifecycleStatus.ACTIVE else null,
    ): DedicatedMapActionContext {
        val event = when (interaction) {
            is MapItemInteractionEvent.Confirmation -> MapItemEventCodec.encode(interaction.itemType, interaction.action)
            is MapItemInteractionEvent.SituationUpdate -> MapItemEventCodec.encode(interaction.itemType, interaction.action)
            is MapItemInteractionEvent.SituationStatus -> MapItemEventCodec.encode(interaction.itemType, interaction.action)
            is MapItemInteractionEvent.Acknowledgement -> MapItemEventCodec.encode(interaction.itemType, interaction.action)
            is MapItemInteractionEvent.CoordinationNote -> MapItemEventCodec.encode(interaction.itemType, interaction.action)
        }
        return DedicatedMapActionContext("g", event, interaction, DedicatedMapActionTarget(true, false),
            MapItemMaterializationSnapshot("g", MapItemIdentity(MapItemType.MARKER, "m"), category,
                if (category == null) MapItemCategoryOrigin.UNRESOLVED else MapItemCategoryOrigin.EXPLICIT_DOMAIN_V1,
                null, "owner", 1, MapItemStateOrigin.EXPLICIT_DOMAIN_V1, lifecycle, windows,
                MapItemTemporalOrigin.EXPLICIT_DOMAIN_V1, null, null, null, lifecycleOrder, null, 0, 0), null, lifecycleOrder)
    }
    private fun meta(t: Long, lt: Long = 0, actor: String = "peer", id: String = "a-$t-$lt-$actor") = MapActionMetadata(id, "m", actor, t, lt)
    private fun confirm(t: Long = 10, lt: Long = 0, actor: String = "peer", id: String = "confirm-$t-$lt-$actor") = MapItemInteractionEvent.Confirmation(MapItemType.MARKER, MapConfirmation(meta(t, lt, actor, id), null, null))
    private fun update(t: Long = 10) = MapItemInteractionEvent.SituationUpdate(MapItemType.MARKER, MapSituationUpdate(meta(t), "content", null, MapSituationUpdateType.SITUATION_CHANGED))
    private fun status(t: Long = 10) = MapItemInteractionEvent.SituationStatus(MapItemType.MARKER, MapSituationStatusUpdate(meta(t), MapSituationLifecycleStatus.NO_LONGER_ACTIVE, null, null))
    private fun ack() = MapItemInteractionEvent.Acknowledgement(MapItemType.MARKER, MapCoordinationAcknowledgement(meta(10)))
    private fun note() = MapItemInteractionEvent.CoordinationNote(MapItemType.MARKER, MapCoordinationNote(meta(10), "note"))
}
