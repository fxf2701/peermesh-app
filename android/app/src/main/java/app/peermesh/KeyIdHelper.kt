package app.peermesh

import app.peermesh.core.id.GroupKeyIdFactory
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventMeta
import app.peermesh.security.crypto.SecureKeyStore

object KeyIdHelper {
    fun attachKeyId(context: android.content.Context, gid: String, core: EventCore): EventCore {
        val keyId = computeKeyId(context, gid) ?: return core
        val meta = core.meta
        if (meta?.kid == keyId) return core
        val nextMeta = (meta ?: EventMeta()).copy(kid = keyId)
        return core.copy(meta = nextMeta)
    }

    private fun computeKeyId(context: android.content.Context, gid: String): String? {
        val store = SecureKeyStore(context)
        val groupKey = store.loadGroupKey(gid) ?: return null
        val salt = store.loadSalt(gid) ?: return null
        return GroupKeyIdFactory.fromKey(groupKey, salt)
    }
}
