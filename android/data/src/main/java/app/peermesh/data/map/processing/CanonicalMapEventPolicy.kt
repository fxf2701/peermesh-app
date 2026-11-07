package app.peermesh.data.map.processing

import app.peermesh.core.model.EventCore
import app.peermesh.data.map.MapItemMaterializationSnapshot

/**
 * DB-aware canonical authorization contract. Implementations must be pure decisions over state
 * loaded by the transaction owner; they must not perform Room writes.
 */
internal interface CanonicalMapEventPolicy {
    fun evaluate(
        gid: String,
        event: EventCore,
        existingState: MapItemMaterializationSnapshot?,
        localActorDid: String?,
    ): CanonicalMapEventPolicyDecision
}

internal class DefaultCanonicalMapEventPolicy : CanonicalMapEventPolicy {
    override fun evaluate(
        gid: String,
        event: EventCore,
        existingState: MapItemMaterializationSnapshot?,
        localActorDid: String?,
    ): CanonicalMapEventPolicyDecision {
        if (event.type.endsWith(".ADD")) {
            return if (existingState == null) CanonicalMapEventPolicyDecision.AcceptCanonicalMutation
            else CanonicalMapEventPolicyDecision.Reject(MapEventRejectionReason.CONFLICTING_ADD)
        }
        if (existingState == null) return CanonicalMapEventPolicyDecision.Reject(MapEventRejectionReason.INVALID_PERSISTED_STATE)
        if (event.actor.did != existingState.ownerDid) {
            if (!event.type.endsWith(".UPD")) return CanonicalMapEventPolicyDecision.Reject(MapEventRejectionReason.OWNER_REQUIRED)
            if (LegacyContribution.parse(event) !is LegacyContribution.ParseResult.Valid) {
                return CanonicalMapEventPolicyDecision.Reject(MapEventRejectionReason.INVALID_LEGACY_CONTRIBUTION)
            }
            val now = event.time.t
            if (existingState.windows.expiresAt != null && now >= existingState.windows.expiresAt) {
                return CanonicalMapEventPolicyDecision.Reject(MapEventRejectionReason.TARGET_EXPIRED)
            }
            if (existingState.windows.interactionUntil != null && now >= existingState.windows.interactionUntil) {
                return CanonicalMapEventPolicyDecision.Reject(MapEventRejectionReason.INTERACTION_WINDOW_CLOSED)
            }
            return CanonicalMapEventPolicyDecision.AcceptLegacyContributionOnly
        }
        if (event.type.endsWith(".UPD") || event.type.endsWith(".DEL")) {
            val now = event.time.t
            if (existingState.windows.expiresAt != null && now >= existingState.windows.expiresAt) {
                return CanonicalMapEventPolicyDecision.Reject(MapEventRejectionReason.TARGET_EXPIRED)
            }
            if (existingState.windows.ownerEditUntil != null && now >= existingState.windows.ownerEditUntil) {
                return CanonicalMapEventPolicyDecision.Reject(MapEventRejectionReason.OWNER_EDIT_WINDOW_CLOSED)
            }
            if (existingState.lifecycle == app.peermesh.core.map.MapSituationLifecycleStatus.NO_LONGER_ACTIVE) {
                return CanonicalMapEventPolicyDecision.Reject(MapEventRejectionReason.LIFECYCLE_REACTIVATION)
            }
        }
        return CanonicalMapEventPolicyDecision.AcceptCanonicalMutation
    }
}

internal object LegacyContribution {
    sealed interface ParseResult {
        data object Absent : ParseResult
        data class Valid(val note: String?, val confidence: Int?) : ParseResult
        data object Invalid : ParseResult
    }

    fun parse(event: EventCore): ParseResult {
        val data = event.data as? kotlinx.serialization.json.JsonObject ?: return ParseResult.Absent
        val hasNote = data.containsKey("note")
        val hasConfidence = data.containsKey("confidence")
        if (!hasNote && !hasConfidence) return ParseResult.Absent
        val note = if (hasNote) {
            val primitive = data["note"] as? kotlinx.serialization.json.JsonPrimitive ?: return ParseResult.Invalid
            primitive.takeIf { it.isString }?.content?.trim()?.ifBlank { return ParseResult.Invalid }
        } else null
        val confidence = if (hasConfidence) {
            val primitive = data["confidence"] as? kotlinx.serialization.json.JsonPrimitive ?: return ParseResult.Invalid
            primitive.content.toIntOrNull()?.takeIf { it in 0..2 } ?: return ParseResult.Invalid
        } else null
        return ParseResult.Valid(note, confidence)
    }

    fun hasContribution(event: EventCore): Boolean {
        return parse(event) is ParseResult.Valid
    }
}

internal sealed interface CanonicalMapEventPolicyDecision {
    data object AcceptCanonicalMutation : CanonicalMapEventPolicyDecision
    data object AcceptLegacyContributionOnly : CanonicalMapEventPolicyDecision
    data class Reject(val reason: MapEventRejectionReason) : CanonicalMapEventPolicyDecision
}
