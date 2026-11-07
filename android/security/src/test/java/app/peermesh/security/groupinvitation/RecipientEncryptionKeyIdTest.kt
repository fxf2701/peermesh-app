package app.peermesh.security.groupinvitation

import app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RecipientEncryptionKeyIdTest {
    @Test
    fun derive_matchesExactDomainAlgorithmAndDelimiterContract() {
        val key = p256KeyPair().let { P256Sec1PublicKeyCodec.encode(it.public) }
        val exactInput = DOMAIN.toByteArray(Charsets.UTF_8) +
            byteArrayOf(0) +
            ALGORITHM.wireValue.toByteArray(Charsets.UTF_8) +
            byteArrayOf(0) +
            key
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(exactInput))

        assertEquals(expected, RecipientEncryptionKeyId.derive(ALGORITHM, key))
        assertNotEquals(
            digest("$DOMAIN/${ALGORITHM.wireValue}".toByteArray() + key),
            RecipientEncryptionKeyId.derive(ALGORITHM, key),
        )
    }

    @Test
    fun derive_isDeterministicFullSha256Base64UrlWithoutPadding() {
        val key = p256KeyPair().let { P256Sec1PublicKeyCodec.encode(it.public) }

        val first = RecipientEncryptionKeyId.derive(ALGORITHM, key)
        val second = RecipientEncryptionKeyId.derive(ALGORITHM, key.copyOf())

        assertEquals(first, second)
        assertEquals(43, first.length)
        assertFalse(first.contains('='))
        assertFalse(first.contains('+'))
        assertFalse(first.contains('/'))
    }

    @Test
    fun derive_changesForDifferentPublicKeysAndDoesNotHashX509Wrapper() {
        val firstPair = p256KeyPair()
        val secondPair = p256KeyPair()
        val firstSec1 = P256Sec1PublicKeyCodec.encode(firstPair.public)

        val first = RecipientEncryptionKeyId.derive(ALGORITHM, firstSec1)
        val second = RecipientEncryptionKeyId.derive(ALGORITHM, P256Sec1PublicKeyCodec.encode(secondPair.public))

        assertNotEquals(first, second)
        assertNotEquals(digest(firstPair.public.encoded), first)
    }

    private fun digest(input: ByteArray): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(input))

    private fun p256KeyPair() = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private companion object {
        const val DOMAIN = "peermesh/recipient-encryption-key-id/v1"
        val ALGORITHM = PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM
    }
}
