package app.peermesh.core.id

import app.peermesh.core.encoding.Base64Url
import java.security.MessageDigest

object GroupKeyIdFactory {
    fun fromKey(groupKey: ByteArray, salt: ByteArray, sizeBytes: Int = 8): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(groupKey)
        digest.update(salt)
        val hash = digest.digest()
        val truncated = hash.copyOfRange(0, sizeBytes.coerceAtMost(hash.size))
        return Base64Url.encode(truncated)
    }
}
