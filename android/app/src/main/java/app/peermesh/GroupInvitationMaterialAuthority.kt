package app.peermesh

import android.content.Context
import android.net.Uri
import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.groupinvitation.InvitationCredential
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.membership.GroupMembershipPolicy
import app.peermesh.security.crypto.SecureKeyStore

/** Presentation-safe summary of local group invite availability. */
data class GroupInvitationMaterialSummary(
    val groupId: String,
    val displayName: String,
)

/**
 * Group-domain authority for locally held invitation material.
 *
 * Nearby presentation code consumes only summaries and opaque credentials. Persistence entities,
 * key identifiers, raw group keys, and invite-link construction stay behind this boundary.
 */
class GroupInvitationMaterialAuthority(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabaseProvider.get(appContext)
    private val secureStore = SecureKeyStore(appContext)

    fun loadEligibleGroups(actorDid: String): List<GroupInvitationMaterialSummary> =
        db.groupDao().listGroupsForActor(actorDid).mapNotNull { group ->
            val removed = GroupMembershipPolicy.isPeerRemoved(db, group.gid, actorDid)
            val key = secureStore.loadGroupKey(group.gid)
            val hasKey = key != null
            key?.fill(0)
            val hasKid = !group.kid.isNullOrBlank()
            val requiresRotation = GroupInvitePolicy.requiresRotation(db, group.gid)
            if (!GroupInvitePolicy.inviteMaterialAvailable(removed, hasKey, hasKid, requiresRotation)) {
                null
            } else {
                GroupInvitationMaterialSummary(
                    groupId = group.gid,
                    displayName = group.name?.takeIf { it.isNotBlank() } ?: group.gid,
                )
            }
        }

    fun resolveCredential(actorDid: String, groupId: String): InvitationCredential? {
        val group = db.groupDao().listGroupsForActor(actorDid).firstOrNull { it.gid == groupId }
            ?: return null
        val removed = GroupMembershipPolicy.isPeerRemoved(db, group.gid, actorDid)
        val kid = group.kid?.takeIf { it.isNotBlank() } ?: return null
        val requiresRotation = GroupInvitePolicy.requiresRotation(db, group.gid)
        val key = secureStore.loadGroupKey(group.gid) ?: return null

        return try {
            if (!GroupInvitePolicy.inviteMaterialAvailable(removed, true, true, requiresRotation)) {
                null
            } else {
                InvitationCredential.of(buildInviteLink(group.gid, kid, key, group.owner_did))
            }
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            key.fill(0)
        }
    }

    private fun buildInviteLink(
        gid: String,
        kid: String,
        key: ByteArray,
        ownerDid: String?,
    ): String {
        val owner = ownerDid
            ?.takeIf { it.isNotBlank() }
            ?.let { "&owner_did=${Uri.encode(it)}" }
            .orEmpty()
        return "peermesh://join?gid=$gid&kid=${Uri.encode(kid)}&group_key=${Uri.encode(Base64Url.encode(key))}$owner"
    }
}
