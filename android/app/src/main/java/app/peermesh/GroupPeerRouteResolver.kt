package app.peermesh

import app.peermesh.data.db.AppDatabase
import app.peermesh.data.membership.GroupPeerRouteStore

object GroupPeerRouteResolver {
    data class Resolution(
        val peerDeviceIds: Map<String, Set<String>>,
        val missingPeerIds: Set<String>,
    )

    fun resolveActivePeerRoutes(
        db: AppDatabase,
        gid: String,
        ownerDid: String?,
        currentActorDid: String,
        excludedPeerIds: Set<String> = emptySet(),
    ): Resolution {
        val activePeerIds = GroupActiveMembers.actorIds(
            db = db,
            gid = gid,
            ownerDid = ownerDid,
            currentActorDid = currentActorDid,
        ) - excludedPeerIds
        val routeStore = GroupPeerRouteStore(db.groupPeerRouteDao())
        val peerDeviceIds = linkedMapOf<String, Set<String>>()
        val missingPeerIds = linkedSetOf<String>()
        activePeerIds.forEach { peerId ->
            val deviceIds = routeStore.listDeviceIds(gid = gid, actorDid = peerId).toSet()
            if (deviceIds.isEmpty()) {
                missingPeerIds += peerId
            } else {
                peerDeviceIds[peerId] = deviceIds
            }
        }
        return Resolution(
            peerDeviceIds = peerDeviceIds,
            missingPeerIds = missingPeerIds,
        )
    }
}
