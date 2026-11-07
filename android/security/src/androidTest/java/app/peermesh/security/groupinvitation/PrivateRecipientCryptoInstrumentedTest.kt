package app.peermesh.security.groupinvitation

import android.os.Build
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class PrivateRecipientCryptoInstrumentedTest {
    private val applicationContext by lazy { ApplicationProvider.getApplicationContext<Context>() }

    @Before
    fun setUp() = clearIdentity()

    @After
    fun tearDown() = clearIdentity()

    @Test
    fun androidKeystoreRecipientIdentityDecryptsPrivatePayload() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val provider = AndroidKeystoreRecipientEncryptionIdentityProvider(applicationContext)
        val identityStatus = provider.getOrCreateIdentity()
        assertTrue(identityStatus is RecipientEncryptionIdentityStatus.Available)
        val identity = (identityStatus as RecipientEncryptionIdentityStatus.Available).identity
        val privateKey = (provider.resolvePrivateKey(identity.keyId) as RecipientPrivateKeyResult.Available).privateKey
        assertNull(privateKey.encoded)
        assertNull(privateKey.format)
        val context = PrivateRecipientCryptoContext(
            protocolVersion = 1,
            algorithmWireValue = PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM.wireValue,
            roomId = "instrumented-room",
            messageId = "instrumented-message",
            cardId = "instrumented-card",
            logicalSenderId = "instrumented-sender",
            targetParticipantId = "instrumented-target",
            targetKeyId = identity.keyId,
            effectiveRoomExpiryEpochMs = Long.MAX_VALUE,
        )
        val crypto = JcaPrivateRecipientCrypto(provider)
        val plaintext = "instrumented-private-invitation".toByteArray(Charsets.UTF_8)

        val encrypted = crypto.encrypt(plaintext, identity, context)
        assertTrue(encrypted is PrivateRecipientEncryptResult.Success)
        val decrypted = crypto.decrypt(
            (encrypted as PrivateRecipientEncryptResult.Success).payload,
            context,
            nowEpochMs = 0L,
        )
        assertTrue(decrypted is PrivateRecipientDecryptResult.Success)
        assertArrayEquals(plaintext, (decrypted as PrivateRecipientDecryptResult.Success).plaintext)
    }

    @Test
    fun replacementIdentityCannotDecryptPayloadForPreviousKey() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val provider = AndroidKeystoreRecipientEncryptionIdentityProvider(applicationContext)
        val original = (provider.getOrCreateIdentity() as RecipientEncryptionIdentityStatus.Available).identity
        val context = context(original.keyId)
        val crypto = JcaPrivateRecipientCrypto(provider)
        val payload = (
            crypto.encrypt("secret".toByteArray(), original, context) as PrivateRecipientEncryptResult.Success
            ).payload

        assertEquals(RecipientIdentityResetResult.Success, provider.resetIdentity(0))
        val replacement = (provider.getOrCreateIdentity() as RecipientEncryptionIdentityStatus.Available).identity
        assertFalse(original.keyId == replacement.keyId)
        assertEquals(
            PrivateRecipientDecryptResult.NotForLocalRecipientKey,
            crypto.decrypt(payload, context, nowEpochMs = 0L),
        )
    }

    @Test
    fun deletedKeystoreIdentityMapsToInvalidatedWithoutEcdh() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val provider = AndroidKeystoreRecipientEncryptionIdentityProvider(applicationContext)
        val identity = (provider.getOrCreateIdentity() as RecipientEncryptionIdentityStatus.Available).identity
        val context = context(identity.keyId)
        val crypto = JcaPrivateRecipientCrypto(provider)
        val payload = (
            crypto.encrypt("secret".toByteArray(), identity, context) as PrivateRecipientEncryptResult.Success
            ).payload
        keyStore().deleteEntry(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS)

        assertEquals(
            PrivateRecipientDecryptResult.RecipientIdentityInvalidated(
                RecipientEncryptionIdentityStatus.Reason.ALIAS_MISSING,
            ),
            crypto.decrypt(payload, context, nowEpochMs = 0L),
        )
    }

    private fun context(targetKeyId: String) = PrivateRecipientCryptoContext(
        protocolVersion = 1,
        algorithmWireValue = PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM.wireValue,
        roomId = "instrumented-room",
        messageId = "instrumented-message",
        cardId = "instrumented-card",
        logicalSenderId = "instrumented-sender",
        targetParticipantId = "instrumented-target",
        targetKeyId = targetKeyId,
        effectiveRoomExpiryEpochMs = Long.MAX_VALUE,
    )

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun clearIdentity() {
        runCatching { keyStore().deleteEntry(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS) }
        applicationContext.getSharedPreferences(
            AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_PREFS,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
    }
}
