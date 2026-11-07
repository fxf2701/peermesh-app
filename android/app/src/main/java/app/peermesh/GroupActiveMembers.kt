package app.peermesh

import app.peermesh.data.db.AppDatabase

object GroupActiveMembers {
    private const val ACTIVE_MEMBER_WINDOW_SECONDS = 2 * 60 * 60L

    fun actorIds(
        db: AppDatabase,
        gid: String,
        ownerDid: String?,
        currentActorDid: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
    ): Set<String> {
        val sinceCreatedT = nowSeconds - ACTIVE_MEMBER_WINDOW_SECONDS
        val removedPeerIds = db.removedMemberDao().listByGroup(gid)
            .map { it.peerId }
            .toSet()
        return buildSet {
            addAll(db.eventsDao().listActiveActorIdsSince(gid, sinceCreatedT))
            ownerDid?.takeIf { it.isNotBlank() }?.let(::add)
            if (currentActorDid.isNotBlank()) add(currentActorDid)
        } - removedPeerIds
    }
}
