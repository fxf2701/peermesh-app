package app.peermesh.core.net

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object RequestSigning {
    fun signHmacSha256Hex(
        secret: String,
        method: String,
        requestTarget: String,
        timestampSeconds: String,
        bodyBytes: ByteArray,
    ): String {
        val canonical = listOf(
            method.uppercase(),
            requestTarget,
            timestampSeconds,
            sha256Hex(bodyBytes),
        ).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).toHexLowercase()
    }

    fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).toHexLowercase()
    }

    private fun ByteArray.toHexLowercase(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }
}
