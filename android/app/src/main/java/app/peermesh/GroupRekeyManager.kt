package app.peermesh

import android.content.Context
import app.peermesh.core.id.GroupKeyIdFactory
import app.peermesh.data.debug.ActivityLog
import app.peermesh.security.crypto.SecureKeyStore
import java.security.SecureRandom

object GroupRekeyManager {
    data class DraftRekey(
        val previousKid: String?,
        val newKid: String,
    )

    fun createDraft(context: Context, gid: String, reason: String): DraftRekey? {
        return runCatching {
            val store = SecureKeyStore(context.applicationContext)
            val salt = store.loadSalt(gid) ?: return null
            val currentKey = store.loadGroupKey(gid)
            val previousKid = if (currentKey != null) GroupKeyIdFactory.fromKey(currentKey, salt) else null
            val nextKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val newKid = GroupKeyIdFactory.fromKey(nextKey, salt)
            store.storeGroupKeyVersion(gid, newKid, nextKey)
            ActivityLog.add(
                label = "Group rekey key created",
                source = "moderation",
                message = "gid=$gid previous_kid=${previousKid ?: "-"} new_kid=$newKid reason=$reason activated=false",
                gid = gid,
            )
            DraftRekey(previousKid = previousKid, newKid = newKid)
        }.getOrNull()
    }
}
