package app.peermesh.map.composition

import android.content.Context
import app.peermesh.DeviceIdProvider
import app.peermesh.data.db.AppDatabase
import app.peermesh.map.application.interaction.DefaultEvaluateMapItemInteractions
import app.peermesh.map.application.interaction.DefaultMapInteractionAvailabilityEvaluator
import app.peermesh.map.application.interaction.EvaluateMapItemInteractions
import app.peermesh.map.application.interaction.LocalMapInteractionSupport
import app.peermesh.map.application.interaction.LocalMapMembershipEvidence
import app.peermesh.map.application.interaction.PersistedLocalMapParticipationProvider
import app.peermesh.map.application.interaction.SystemMapAvailabilityClock
import app.peermesh.map.application.interaction.UnknownMapPeerCapabilityProvider

/**
 * M3A2 composition skeleton.
 *
 * Codex must reuse accepted local identity/membership read seams. Do not infer peer support from
 * BLE connection, relay availability, route stores, or observed active members.
 */
object MapInteractionAvailabilityFactory {
    fun create(context: Context, database: AppDatabase): EvaluateMapItemInteractions =
        DefaultEvaluateMapItemInteractions(
            participationProvider = PersistedLocalMapParticipationProvider(
                actorDidProvider = { DeviceIdProvider.getActiveActor(context.applicationContext) },
                evidence = RoomLocalMapMembershipEvidence(database),
            ),
            localSupportProvider = { LocalMapInteractionSupport.allSupported() },
            peerCapabilityProvider = UnknownMapPeerCapabilityProvider,
            clock = SystemMapAvailabilityClock,
            evaluator = DefaultMapInteractionAvailabilityEvaluator(),
        )

    private class RoomLocalMapMembershipEvidence(
        private val database: AppDatabase,
    ) : LocalMapMembershipEvidence {
        override fun isRemoved(groupId: String, actorDid: String): Boolean =
            database.removedMemberDao().count(groupId, actorDid) > 0

        override fun hasMemberProfile(groupId: String, actorDid: String): Boolean =
            database.userProfileDao().find(groupId, actorDid) != null
    }
}
