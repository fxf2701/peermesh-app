package app.peermesh.core.id

import app.peermesh.core.encoding.Base64Url
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object GidFactory {
    fun derive(passphrase: CharArray, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(String(passphrase).toByteArray(StandardCharsets.UTF_8))
        digest.update(salt)
        return Base64Url.encode(digest.digest())
    }
}
