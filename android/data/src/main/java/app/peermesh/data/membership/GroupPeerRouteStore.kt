package app.peermesh.data.membership

import app.peermesh.data.db.dao.GroupPeerRouteDao
import app.peermesh.data.db.entity.GroupPeerRouteEntity

class GroupPeerRouteStore(
    private val dao: GroupPeerRouteDao,
) {
    fun record(gid: String, actorDid: String, deviceId: String, nowSeconds: Long): Boolean {
        if (gid.isBlank() || actorDid.isBlank() || deviceId.isBlank()) return false
        val existing = dao.find(gid = gid, actorDid = actorDid, deviceId = deviceId)
        dao.upsert(
            GroupPeerRouteEntity(
                gid = gid,
                actor_did = actorDid,
                device_id = deviceId,
                first_seen_at = existing?.first_seen_at ?: nowSeconds,
                updated_at = nowSeconds,
            )
        )
        return existing == null
    }

    fun listDeviceIds(gid: String, actorDid: String, limit: Int = 8): List<String> {
        if (gid.isBlank() || actorDid.isBlank()) return emptyList()
        return dao.listDeviceIds(gid = gid, actorDid = actorDid, limit = limit)
    }
}
