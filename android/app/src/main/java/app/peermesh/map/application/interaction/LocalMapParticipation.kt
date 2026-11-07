package app.peermesh.map.application.interaction

sealed interface LocalMapParticipation {
    data object CurrentMember : LocalMapParticipation
    data object NotMember : LocalMapParticipation
    data object Unknown : LocalMapParticipation
    data object Unavailable : LocalMapParticipation
}

/** Advisory only. RoomMapEventProcessor must recheck authoritative membership in its transaction. */
fun interface LocalMapParticipationProvider {
    fun participationFor(groupId: String): LocalMapParticipation
}

/** Persisted evidence only; absence of either row is not itself negative membership evidence. */
interface LocalMapMembershipEvidence {
    fun isRemoved(groupId: String, actorDid: String): Boolean
    fun hasMemberProfile(groupId: String, actorDid: String): Boolean
}

class PersistedLocalMapParticipationProvider(
    private val actorDidProvider: () -> String?,
    private val evidence: LocalMapMembershipEvidence,
) : LocalMapParticipationProvider {
    override fun participationFor(groupId: String): LocalMapParticipation {
        val actorDid = actorDidProvider()?.takeIf { it.isNotBlank() }
            ?: return LocalMapParticipation.Unknown
        if (groupId.isBlank()) return LocalMapParticipation.Unknown
        return try {
            when {
                evidence.isRemoved(groupId, actorDid) -> LocalMapParticipation.NotMember
                evidence.hasMemberProfile(groupId, actorDid) -> LocalMapParticipation.CurrentMember
                else -> LocalMapParticipation.Unknown
            }
        } catch (_: Exception) {
            LocalMapParticipation.Unavailable
        }
    }
}
