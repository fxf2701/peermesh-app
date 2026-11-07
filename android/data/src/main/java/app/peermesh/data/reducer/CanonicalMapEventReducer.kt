package app.peermesh.data.reducer

import app.peermesh.core.model.EventCore
import app.peermesh.data.map.processing.CanonicalMapEventPolicyDecision
import app.peermesh.data.db.dao.MapDao
import app.peermesh.data.db.dao.MapItemVoteDao
import app.peermesh.data.db.entity.MapItemVoteEntity
import app.peermesh.data.map.processing.MapEventOrderingPolicy
import app.peermesh.core.types.EventTypes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Canonical ADD/UPD/DEL reducer boundary introduced by M2C1. */
internal interface CanonicalMapEventReducer {
    fun apply(
        gid: String,
        event: EventCore,
        decision: CanonicalMapEventPolicyDecision,
    ): CanonicalMapReductionResult
}

/** M2C1 adapter around the accepted legacy reducer storage, with canonical authorization applied. */
internal class RoomCanonicalMapEventReducer(
    private val mapDao: MapDao,
    private val voteDao: MapItemVoteDao,
) : CanonicalMapEventReducer {
    private val legacy = MapReducer(mapDao, voteDao, includeEventIdTieBreak = true, preserveTemporalOnUpdate = true, legacyNoExpiryOnAdd = true, acceptExplicitExpiryOnAdd = false)

    override fun apply(gid: String, event: EventCore, decision: CanonicalMapEventPolicyDecision): CanonicalMapReductionResult {
        if (decision is CanonicalMapEventPolicyDecision.AcceptLegacyContributionOnly) {
            return applyContribution(gid, event)
        }
        if (decision is CanonicalMapEventPolicyDecision.Reject) return CanonicalMapReductionResult.Rejected(decision.reason.persistedValue)
        val target = targetId(event) ?: return CanonicalMapReductionResult.Rejected("missing_target")
        val marker = mapDao.findMarker(gid, target)
        val area = mapDao.findArea(gid, target)
        val current = marker?.let { app.peermesh.data.db.MapActionOrderKey(it.last_event_t, it.last_event_lt, it.last_event_actor, it.last_event_id) }
            ?: area?.let { app.peermesh.data.db.MapActionOrderKey(it.last_event_t, it.last_event_lt, it.last_event_actor, it.last_event_id) }
        if (current != null && MapEventOrderingPolicy.orderKey(event) <= current) return CanonicalMapReductionResult.NoChange
        if (event.type.endsWith(".UPD") && (marker?.status == ReducerConstants.STATUS_DELETED || area?.status == ReducerConstants.STATUS_DELETED)) {
            return CanonicalMapReductionResult.Rejected("target_deleted")
        }
        legacy.apply(event, gid)
        val updated = if (event.type.contains("MARKER")) mapDao.findMarker(gid, target)?.last_event_id
            else mapDao.findArea(gid, target)?.last_event_id
        return if (updated == event.id) CanonicalMapReductionResult.Applied else CanonicalMapReductionResult.NoChange
    }

    private fun applyContribution(gid: String, event: EventCore): CanonicalMapReductionResult {
        val target = targetId(event) ?: return CanonicalMapReductionResult.Rejected("missing_target")
        val parsed = app.peermesh.data.map.processing.LegacyContribution.parse(event)
        if (parsed !is app.peermesh.data.map.processing.LegacyContribution.ParseResult.Valid) {
            return CanonicalMapReductionResult.Rejected(app.peermesh.data.map.processing.MapEventRejectionReason.INVALID_LEGACY_CONTRIBUTION.persistedValue)
        }
        val existing = voteDao.find(gid, target, event.actor.did)
        val note = parsed.note ?: existing?.note
        val validConfidence = parsed.confidence ?: existing?.confidence ?: MapReducer.CONF_REPORTED
        if (validConfidence !in MapReducer.CONF_UNCERTAIN..MapReducer.CONF_OBSERVED) return CanonicalMapReductionResult.NoChange
        voteDao.upsert(MapItemVoteEntity(gid, target, if (event.type.contains("MARKER")) "marker" else "area",
            event.actor.did, note, validConfidence, existing?.created_t ?: event.time.t, event.time.t))
        return CanonicalMapReductionResult.LegacyContributionApplied
    }

    private fun primitive(data: JsonObject, key: String): String? = (data[key] as? JsonPrimitive)?.content
    private fun targetId(event: EventCore): String? {
        val data = event.data as? JsonObject ?: return null
        return event.ref.target ?: primitive(data, if (event.type.contains("MARKER")) "marker_id" else "area_id")
            ?: event.id.takeIf { event.type.endsWith(".ADD") }
    }
}

internal sealed interface CanonicalMapReductionResult {
    data object Applied : CanonicalMapReductionResult
    data object LegacyContributionApplied : CanonicalMapReductionResult
    data object NoChange : CanonicalMapReductionResult
    data class Rejected(val reason: String) : CanonicalMapReductionResult
}
