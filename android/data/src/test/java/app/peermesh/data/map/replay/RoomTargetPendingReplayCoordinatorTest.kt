package app.peermesh.data.map.replay

import app.peermesh.core.map.MapItemAction
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.data.db.MapActionOrderKey
import app.peermesh.data.db.MapItemCategoryOrigin
import app.peermesh.data.db.MapItemStateOrigin
import app.peermesh.data.db.MapItemTemporalOrigin
import app.peermesh.data.map.MapItemActionRead
import app.peermesh.data.map.MapItemIdentity
import app.peermesh.data.map.MapItemMaterializationSnapshot
import app.peermesh.data.map.MapItemWindows
import app.peermesh.data.map.processing.MapEventRejectionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingActionSemanticEvaluatorTest {
    @Test fun `event time drives exclusive temporal boundaries`() {
        assertRejected(action(MapItemAction.CONFIRM, 20), snapshot(windows = MapItemWindows(20, null, 30)),
            null, MapEventRejectionReason.TARGET_EXPIRED)
        assertRejected(action(MapItemAction.CONFIRM, 20), snapshot(windows = MapItemWindows(30, null, 20)),
            null, MapEventRejectionReason.INTERACTION_WINDOW_CLOSED)
        assertApplied(action(MapItemAction.CONFIRM, 19), snapshot(windows = MapItemWindows(20, null, 20)), null)
    }

    @Test fun `earliest resolution is an irreversible complete-order cutoff`() {
        val cutoff = MapActionOrderKey(20, 2, "m", "resolution")
        assertApplied(action(MapItemAction.CONFIRM, 20, 1, "z", "before-lt"), snapshot(), cutoff)
        assertApplied(action(MapItemAction.CONFIRM, 20, 2, "a", "before-actor"), snapshot(), cutoff)
        assertRejected(action(MapItemAction.CONFIRM, 20, 2, "m", "resolution"), snapshot(), cutoff,
            MapEventRejectionReason.TARGET_RESOLVED)
        assertRejected(action(MapItemAction.CONFIRM, 20, 2, "z", "after-actor"), snapshot(), cutoff,
            MapEventRejectionReason.TARGET_RESOLVED)
    }

    @Test fun `repeated resolution remains applicable and cannot reactivate`() {
        val cutoff = MapActionOrderKey(10, 0, "a", "first")
        assertApplied(action(MapItemAction.UPDATE_STATUS, 20,
            lifecycle = MapSituationLifecycleStatus.NO_LONGER_ACTIVE), snapshot(), cutoff)
        assertRejected(action(MapItemAction.UPDATE_STATUS, 20,
            lifecycle = MapSituationLifecycleStatus.ACTIVE), snapshot(), cutoff,
            MapEventRejectionReason.LIFECYCLE_REACTIVATION)
    }

    @Test fun `target dependent rejection vocabulary is stable`() {
        assertRejected(action(MapItemAction.CONFIRM, 10), snapshot(category = null), null,
            MapEventRejectionReason.TARGET_CATEGORY_UNRESOLVED)
        assertRejected(action(MapItemAction.ACKNOWLEDGE, 10), snapshot(), null,
            MapEventRejectionReason.ACTION_NOT_ALLOWED_FOR_CATEGORY)
        assertRejected(action(MapItemAction.CONFIRM, 10), snapshot(), null,
            MapEventRejectionReason.TARGET_DELETED, deleted = true)
    }

    private fun assertApplied(action: MapItemActionRead, snapshot: MapItemMaterializationSnapshot,
                              cutoff: MapActionOrderKey?) = assertTrue(DefaultPendingActionSemanticEvaluator.evaluate(
        PendingActionSemanticInput(action, snapshot, cutoff, false)) is PendingActionSemanticDecision.Apply)

    private fun assertRejected(action: MapItemActionRead, snapshot: MapItemMaterializationSnapshot,
                               cutoff: MapActionOrderKey?, reason: MapEventRejectionReason, deleted: Boolean = false) {
        val decision = DefaultPendingActionSemanticEvaluator.evaluate(PendingActionSemanticInput(action, snapshot, cutoff, deleted))
        assertEquals(reason, (decision as PendingActionSemanticDecision.Reject).reason)
    }

    private fun action(type: MapItemAction, t: Long, lt: Long = 0, actor: String = "peer",
                       eventId: String = "event", lifecycle: MapSituationLifecycleStatus? = null) =
        MapItemActionRead("g", MapItemIdentity(MapItemType.MARKER, "m"), type,
            MapActionOrderKey(t, lt, actor, eventId), null, null, null, lifecycle)

    private fun snapshot(category: MapItemCategory? = MapItemCategory.SITUATION,
                         windows: MapItemWindows = MapItemWindows(null, null, null)) =
        MapItemMaterializationSnapshot("g", MapItemIdentity(MapItemType.MARKER, "m"), category,
            if (category == null) MapItemCategoryOrigin.UNRESOLVED else MapItemCategoryOrigin.EXPLICIT_DOMAIN_V1,
            null, "owner", if (category == null) null else 1, MapItemStateOrigin.EXPLICIT_DOMAIN_V1,
            if (category == MapItemCategory.SITUATION) MapSituationLifecycleStatus.ACTIVE else null,
            windows, MapItemTemporalOrigin.EXPLICIT_DOMAIN_V1, null, null, null, null, null, 0, 0)
}
