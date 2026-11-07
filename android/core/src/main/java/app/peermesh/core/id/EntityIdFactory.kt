package app.peermesh.core.id

import app.peermesh.core.encoding.Base64Url
import java.security.MessageDigest
import java.security.SecureRandom

object EntityIdFactory {
    private val random = SecureRandom()

    fun markerId(gid: String, deviceId: String, ts: Long, nonce: ByteArray = newNonce()): String {
        return hashId("marker", gid, deviceId, ts, nonce)
    }

    fun areaId(gid: String, deviceId: String, ts: Long, nonce: ByteArray = newNonce()): String {
        return hashId("area", gid, deviceId, ts, nonce)
    }

    private fun newNonce(bytes: Int = 16): ByteArray {
        val nonce = ByteArray(bytes)
        random.nextBytes(nonce)
        return nonce
    }

    private fun hashId(prefix: String, gid: String, deviceId: String, ts: Long, nonce: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(prefix.toByteArray(Charsets.UTF_8))
        digest.update(gid.toByteArray(Charsets.UTF_8))
        digest.update(deviceId.toByteArray(Charsets.UTF_8))
        digest.update(ts.toString().toByteArray(Charsets.UTF_8))
        digest.update(nonce)
        return Base64Url.encode(digest.digest())
    }
}
