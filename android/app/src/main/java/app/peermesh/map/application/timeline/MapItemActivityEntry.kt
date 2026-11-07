package app.peermesh.map.application.timeline

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.core.map.MapSituationUpdateType
import app.peermesh.map.application.detail.MapItemIdentity

/** UI-safe historical projection of one applied dedicated map action. */
data class MapItemActivityEntry(
    val entryId: String,
    val itemIdentity: MapItemIdentity,
    val type: MapItemActivityType,
    val actor: MapItemActivityActor,
    val actionTimestampSeconds: Long,
    val informationSource: MapInformationSource?,
    val note: String?,
    val updateDisposition: MapSituationUpdateType?,
    val targetLifecycle: MapSituationLifecycleStatus?,
)

/** Stable application vocabulary. Android resources own final localized copy. */
enum class MapItemActivityType {
    SITUATION_CONFIRMED,
    SITUATION_UPDATED,
    SITUATION_MARKED_NO_LONGER_ACTIVE,
    COORDINATION_ACKNOWLEDGED,
    COORDINATION_NOTE_ADDED,
}

/**
 * M3B1 deliberately provides only deterministic actor attribution.
 * Rich profile/contact resolution is outside this phase.
 */
data class MapItemActivityActor(
    val stableId: String,
    val displayValue: String,
)
