package app.peermesh.core.id

import app.peermesh.core.encoding.Base64Url
import java.security.SecureRandom
import java.security.MessageDigest

object EventIdFactory {
    private val random = SecureRandom()

    fun generateNonce(bytes: Int = 16): ByteArray {
        val nonce = ByteArray(bytes)
        random.nextBytes(nonce)
        return nonce
    }

    fun generate(gid: String, deviceId: String, ts: Long, nonce: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(gid.toByteArray(Charsets.UTF_8))
        digest.update(deviceId.toByteArray(Charsets.UTF_8))
        digest.update(ts.toString().toByteArray(Charsets.UTF_8))
        digest.update(nonce)
        val hash = digest.digest()
        return Base64Url.encode(hash)
    }
}
