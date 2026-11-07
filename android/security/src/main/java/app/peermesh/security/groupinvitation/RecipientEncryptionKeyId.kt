package app.peermesh.security.groupinvitation

import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm
import java.security.MessageDigest

object RecipientEncryptionKeyId {
    private const val DOMAIN = "peermesh/recipient-encryption-key-id/v1"
    private const val SEPARATOR: Byte = 0

    fun derive(
        algorithm: PrivateInvitationEncryptionAlgorithm,
        publicKeySec1: ByteArray,
    ): String {
        P256Sec1PublicKeyCodec.validate(publicKeySec1)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN.toByteArray(Charsets.UTF_8))
        digest.update(SEPARATOR)
        digest.update(algorithm.wireValue.toByteArray(Charsets.UTF_8))
        digest.update(SEPARATOR)
        digest.update(publicKeySec1)
        return Base64Url.encode(digest.digest())
    }
}
