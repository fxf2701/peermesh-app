package app.peermesh.security.groupinvitation

import app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Test

class RecipientEncryptionIdentityTest {
    @Test
    fun constructorAndGetterDefensivelyCopyPublicKey() {
        val input = publicKey()
        val identity = identity(input)
        val original = identity.publicKeySec1

        input[1] = (input[1].toInt() xor 1).toByte()
        val returned = identity.publicKeySec1
        returned[2] = (returned[2].toInt() xor 1).toByte()

        assertNotSame(returned, identity.publicKeySec1)
        assertArrayEquals(original, identity.publicKeySec1)
    }

    @Test
    fun equalityAndHashCodeUseByteContentRatherThanArrayReference() {
        val key = publicKey()
        val first = identity(key)
        val second = identity(key.copyOf())

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun diagnosticStringRedactsPublicKeyAndFullKeyId() {
        val identity = identity(publicKey())

        assertFalse(identity.toString().contains(identity.keyId))
        assertFalse(identity.toString().contains(identity.publicKeySec1.joinToString()))
    }

    private fun identity(key: ByteArray): RecipientEncryptionIdentity {
        val algorithm = PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM
        return RecipientEncryptionIdentity(algorithm, RecipientEncryptionKeyId.derive(algorithm, key), key)
    }

    private fun publicKey(): ByteArray = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        P256Sec1PublicKeyCodec.encode(generateKeyPair().public)
    }
}
