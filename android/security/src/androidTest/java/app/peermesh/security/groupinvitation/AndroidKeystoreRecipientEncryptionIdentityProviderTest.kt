package app.peermesh.security.groupinvitation

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.crypto.KeyAgreement
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreRecipientEncryptionIdentityProviderTest {
    private val context by lazy { ApplicationProvider.getApplicationContext<Context>() }

    @Before
    fun setUp() = clearIdentity()

    @After
    fun tearDown() = clearIdentity()

    @Test
    fun api31_getOrCreateIsStableAcrossCallsAndProviderRecreation() {
        assumeApi31()
        val first = available(provider().getOrCreateIdentity())
        val second = available(provider().getOrCreateIdentity())

        assertEquals(first.keyId, second.keyId)
        assertArrayEquals(first.publicKeySec1, second.publicKeySec1)
        assertEquals(65, first.publicKeySec1.size)
        assertEquals(0x04, first.publicKeySec1[0].toInt())
        assertEquals(first.keyId, RecipientEncryptionKeyId.derive(first.algorithm, first.publicKeySec1))
    }

    @Test
    fun api31_concurrentProvidersConvergeOnOneIdentity() {
        assumeApi31()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll(
                List(16) { Callable { available(provider().getOrCreateIdentity()) } },
            ).map { it.get() }

            assertEquals(1, results.map { it.keyId }.toSet().size)
            assertEquals(1, results.map { it.publicKeySec1.contentHashCode() }.toSet().size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun api31_privateKeyIsNonExportableAndPerforms32ByteEcdh() {
        assumeApi31()
        val identity = available(provider().getOrCreateIdentity())
        val privateKey = (provider().resolvePrivateKey(identity.keyId) as RecipientPrivateKeyResult.Available).privateKey

        assertNull(privateKey.encoded)
        assertNull(privateKey.format)
        val peer = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peer.public, true)
        val secret = agreement.generateSecret()
        try {
            assertEquals(32, secret.size)
            assertFalse(secret.all { it == 0.toByte() })
        } finally {
            secret.fill(0)
        }
    }

    @Test
    fun api31_resolveRejectsWrongKeyIdAndDoesNotCreateWhenMissing() {
        assumeApi31()
        assertTrue(provider().resolvePrivateKey("missing") is RecipientPrivateKeyResult.NotFound)
        assertFalse(keyStore().containsAlias(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS))

        val identity = available(provider().getOrCreateIdentity())
        assertTrue(provider().resolvePrivateKey("wrong") is RecipientPrivateKeyResult.KeyIdMismatch)
        assertTrue(provider().resolvePrivateKey(identity.keyId) is RecipientPrivateKeyResult.Available)
    }

    @Test
    fun api31_aliasWithMissingMetadataIsAdoptedDeterministically() {
        assumeApi31()
        val original = available(provider().getOrCreateIdentity())
        metadata().edit().clear().commit()

        val adopted = available(provider().getOrCreateIdentity())

        assertEquals(original.keyId, adopted.keyId)
        assertArrayEquals(original.publicKeySec1, adopted.publicKeySec1)
        assertTrue(metadata().getBoolean(AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_CREATED, false))
    }

    @Test
    fun api31_metadataWithMissingAliasIsInvalidatedUntilExplicitReset() {
        assumeApi31()
        val original = available(provider().getOrCreateIdentity())
        keyStore().deleteEntry(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS)

        val missing = provider().getOrCreateIdentity()
        assertTrue(missing is RecipientEncryptionIdentityStatus.Invalidated)
        assertEquals(
            RecipientEncryptionIdentityStatus.Reason.ALIAS_MISSING,
            (missing as RecipientEncryptionIdentityStatus.Invalidated).reason,
        )

        assertTrue(provider().resetIdentity(0) is RecipientIdentityResetResult.Success)
        val replacement = available(provider().getOrCreateIdentity())
        assertFalse(original.keyId == replacement.keyId)
    }

    @Test
    fun api31_metadataMismatchNeverRotatesOrOverwritesAlias() {
        assumeApi31()
        val original = available(provider().getOrCreateIdentity())
        metadata().edit()
            .putString(AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_KEY_ID, "A".repeat(43))
            .commit()

        val mismatch = provider().getOrCreateIdentity()

        assertTrue(mismatch is RecipientEncryptionIdentityStatus.Invalidated)
        assertEquals("A".repeat(43), metadata().getString(AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_KEY_ID, null))
        writeValidMetadata(original.keyId)
        assertEquals(original.keyId, available(provider().getOrCreateIdentity()).keyId)
    }

    @Test
    fun api31_wrongPurposeAliasIsInvalidatedWithoutReplacement() {
        assumeApi31()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(
                    AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN,
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generateKeyPair()
        }
        val originalPublic = keyStore().getCertificate(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS).publicKey.encoded

        assertTrue(provider().getOrCreateIdentity() is RecipientEncryptionIdentityStatus.Invalidated)
        assertArrayEquals(
            originalPublic,
            keyStore().getCertificate(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS).publicKey.encoded,
        )
    }

    @Test
    fun api31_transientKeystoreFailureDoesNotRotateExistingIdentity() {
        assumeApi31()
        val original = available(provider().getOrCreateIdentity())
        val originalPublic = keyStore().getCertificate(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS).publicKey.encoded
        val failingProvider = AndroidKeystoreRecipientEncryptionIdentityProvider(
            context = context,
            keyStoreLoader = { throw KeyStoreException("test-only provider failure") },
        )

        val result = failingProvider.getOrCreateIdentity()

        assertEquals(
            RecipientEncryptionIdentityStatus.TemporarilyUnavailable(
                RecipientEncryptionIdentityStatus.Reason.KEYSTORE_UNAVAILABLE,
            ),
            result,
        )
        assertArrayEquals(
            originalPublic,
            keyStore().getCertificate(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS).publicKey.encoded,
        )
        assertEquals(original.keyId, available(provider().getOrCreateIdentity()).keyId)
    }

    @Test
    fun api31_metadataClearFailurePreservesAliasMetadataAndResolvedIdentity() {
        assumeApi31()
        val original = available(provider().getOrCreateIdentity())
        val originalPublic = original.publicKeySec1
        val deleteCalls = AtomicInteger(0)
        val resetProvider = AndroidKeystoreRecipientEncryptionIdentityProvider(
            context = context,
            keyStoreLoader = ::keyStore,
            metadataClearer = { false },
            aliasDeleter = { deleteCalls.incrementAndGet() },
        )

        val result = resetProvider.resetIdentity(0)

        assertEquals(
            RecipientIdentityResetResult.TemporarilyUnavailable(
                RecipientEncryptionIdentityStatus.Reason.METADATA_WRITE_FAILED,
            ),
            result,
        )
        assertEquals(0, deleteCalls.get())
        assertTrue(keyStore().containsAlias(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS))
        assertTrue(metadata().getBoolean(AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_CREATED, false))
        assertTrue(provider().resolvePrivateKey(original.keyId) is RecipientPrivateKeyResult.Available)
        val reloaded = available(provider().getOrCreateIdentity())
        assertEquals(original.keyId, reloaded.keyId)
        assertArrayEquals(originalPublic, reloaded.publicKeySec1)
    }

    @Test
    fun api31_aliasDeletionFailureClearsMetadataAndReAdoptsExistingIdentity() {
        assumeApi31()
        val original = available(provider().getOrCreateIdentity())
        val originalPublic = original.publicKeySec1
        val deleteCalls = AtomicInteger(0)
        val resetProvider = AndroidKeystoreRecipientEncryptionIdentityProvider(
            context = context,
            keyStoreLoader = ::keyStore,
            metadataClearer = ::clearIdentityMetadataForTest,
            aliasDeleter = {
                deleteCalls.incrementAndGet()
                throw KeyStoreException("test-only alias deletion failure")
            },
        )

        val result = resetProvider.resetIdentity(0)

        assertEquals(
            RecipientIdentityResetResult.TemporarilyUnavailable(
                RecipientEncryptionIdentityStatus.Reason.KEYSTORE_UNAVAILABLE,
            ),
            result,
        )
        assertEquals(1, deleteCalls.get())
        assertTrue(keyStore().containsAlias(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS))
        assertFalse(metadata().contains(AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_CREATED))
        val adopted = available(provider().getOrCreateIdentity())
        assertEquals(original.keyId, adopted.keyId)
        assertArrayEquals(originalPublic, adopted.publicKeySec1)
    }

    @Test
    fun api31_deleteThenThrowIsReconciledAsSuccessfulReset() {
        assumeApi31()
        val original = available(provider().getOrCreateIdentity())
        val resetProvider = AndroidKeystoreRecipientEncryptionIdentityProvider(
            context = context,
            keyStoreLoader = ::keyStore,
            metadataClearer = ::clearIdentityMetadataForTest,
            aliasDeleter = { keyStore ->
                keyStore.deleteEntry(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS)
                throw KeyStoreException("test-only post-delete failure")
            },
        )

        val result = resetProvider.resetIdentity(0)

        assertEquals(RecipientIdentityResetResult.Success, result)
        assertFalse(keyStore().containsAlias(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS))
        assertFalse(metadata().contains(AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_CREATED))
        assertFalse(original.keyId == available(provider().getOrCreateIdentity()).keyId)
    }

    @Test
    fun api31_aliasPostconditionCheckFailureReturnsTemporaryWithoutCreation() {
        assumeApi31()
        val original = available(provider().getOrCreateIdentity())
        val resetProvider = AndroidKeystoreRecipientEncryptionIdentityProvider(
            context = context,
            keyStoreLoader = ::keyStore,
            metadataClearer = ::clearIdentityMetadataForTest,
            aliasDeleter = { throw KeyStoreException("test-only delete failure") },
            aliasExistsChecker = { throw KeyStoreException("test-only check failure") },
        )

        val result = resetProvider.resetIdentity(0)

        assertEquals(
            RecipientIdentityResetResult.TemporarilyUnavailable(
                RecipientEncryptionIdentityStatus.Reason.KEYSTORE_UNAVAILABLE,
            ),
            result,
        )
        assertTrue(keyStore().containsAlias(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS))
        assertFalse(metadata().contains(AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_CREATED))
        assertEquals(original.keyId, available(provider().getOrCreateIdentity()).keyId)
    }

    @Test
    fun api31_postDeleteIndeterminateStateSurvivesProviderRecreationWithoutReplacement() {
        assumeApi31()
        provider().getOrCreateIdentity()
        val resetProvider = AndroidKeystoreRecipientEncryptionIdentityProvider(
            context = context,
            keyStoreLoader = ::keyStore,
            metadataClearer = ::clearIdentityMetadataForTest,
            aliasDeleter = { keyStore ->
                keyStore.deleteEntry(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS)
                throw KeyStoreException("test-only post-delete failure")
            },
            aliasExistsChecker = { throw KeyStoreException("test-only unavailable postcondition") },
        )

        val reset = resetProvider.resetIdentity(0)

        assertTrue(reset is RecipientIdentityResetResult.TemporarilyUnavailable)
        assertFalse(keyStore().containsAlias(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS))
        assertFalse(metadata().contains(AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_CREATED))
        assertEquals(
            AndroidKeystoreRecipientEncryptionIdentityProvider.RESET_OUTCOME_INDETERMINATE,
            metadata().getString(AndroidKeystoreRecipientEncryptionIdentityProvider.RESET_STATE_KEY, null),
        )
        val recovered = provider().getOrCreateIdentity()
        assertEquals(
            RecipientEncryptionIdentityStatus.Invalidated(
                RecipientEncryptionIdentityStatus.Reason.RESET_RECOVERY_REQUIRED,
            ),
            recovered,
        )
        assertFalse(keyStore().containsAlias(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS))
    }

    @Test
    fun api31_indeterminateMarkerWithAliasPresentReAdoptsIdentityAndClearsMarker() {
        assumeApi31()
        val original = available(provider().getOrCreateIdentity())
        assertTrue(clearIdentityMetadataForTest())
        assertTrue(writeResetStateForTest(AndroidKeystoreRecipientEncryptionIdentityProvider.RESET_OUTCOME_INDETERMINATE))

        val adopted = available(provider().getOrCreateIdentity())

        assertEquals(original.keyId, adopted.keyId)
        assertArrayEquals(original.publicKeySec1, adopted.publicKeySec1)
        assertFalse(metadata().contains(AndroidKeystoreRecipientEncryptionIdentityProvider.RESET_STATE_KEY))
        assertTrue(metadata().contains(AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_CREATED))
    }

    @Test
    fun api31_explicitRecoveryClearsAbsentAliasMarkerBeforeNextGeneration() {
        assumeApi31()
        val original = available(provider().getOrCreateIdentity())
        keyStore().deleteEntry(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS)
        assertTrue(clearIdentityMetadataForTest())
        assertTrue(writeResetStateForTest(AndroidKeystoreRecipientEncryptionIdentityProvider.RESET_OUTCOME_INDETERMINATE))
        assertTrue(provider().getOrCreateIdentity() is RecipientEncryptionIdentityStatus.Invalidated)

        assertEquals(RecipientIdentityResetResult.Success, provider().resetIdentity(0))
        assertFalse(metadata().contains(AndroidKeystoreRecipientEncryptionIdentityProvider.RESET_STATE_KEY))
        assertFalse(keyStore().containsAlias(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS))
        val replacement = available(provider().getOrCreateIdentity())
        assertFalse(original.keyId == replacement.keyId)
    }

    @Test
    fun api31_activeRoomBlocksIndeterminateRecoveryAndPreservesMarker() {
        assumeApi31()
        assertTrue(writeResetStateForTest(AndroidKeystoreRecipientEncryptionIdentityProvider.RESET_OUTCOME_INDETERMINATE))

        assertEquals(RecipientIdentityResetResult.BlockedByActiveNearbyRooms(1), provider().resetIdentity(1))
        assertEquals(
            AndroidKeystoreRecipientEncryptionIdentityProvider.RESET_OUTCOME_INDETERMINATE,
            metadata().getString(AndroidKeystoreRecipientEncryptionIdentityProvider.RESET_STATE_KEY, null),
        )
        assertFalse(keyStore().containsAlias(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS))
    }

    @Test
    fun api31_malformedResetMarkerBlocksAutomaticGeneration() {
        assumeApi31()
        metadata().edit()
            .putString(AndroidKeystoreRecipientEncryptionIdentityProvider.RESET_STATE_KEY, "UNKNOWN")
            .commit()

        assertEquals(
            RecipientEncryptionIdentityStatus.Invalidated(
                RecipientEncryptionIdentityStatus.Reason.RESET_RECOVERY_REQUIRED,
            ),
            provider().getOrCreateIdentity(),
        )
        assertFalse(keyStore().containsAlias(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS))
    }

    @Test
    fun api31_activeRoomBlockOccursBeforeResetMutations() {
        assumeApi31()
        val original = available(provider().getOrCreateIdentity())
        val metadataClearCalls = AtomicInteger(0)
        val deleteCalls = AtomicInteger(0)
        val resetProvider = AndroidKeystoreRecipientEncryptionIdentityProvider(
            context = context,
            keyStoreLoader = ::keyStore,
            metadataClearer = {
                metadataClearCalls.incrementAndGet()
                clearIdentityMetadataForTest()
            },
            aliasDeleter = { deleteCalls.incrementAndGet() },
        )

        val result = resetProvider.resetIdentity(1)

        assertEquals(RecipientIdentityResetResult.BlockedByActiveNearbyRooms(1), result)
        assertEquals(0, metadataClearCalls.get())
        assertEquals(0, deleteCalls.get())
        assertTrue(keyStore().containsAlias(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS))
        assertEquals(original.keyId, available(provider().getOrCreateIdentity()).keyId)
    }

    @Test
    fun api31_resetIsBlockedByActiveRoomAndDoesNotDeleteAlias() {
        assumeApi31()
        val identity = available(provider().getOrCreateIdentity())

        val result = provider().resetIdentity(1)

        assertEquals(RecipientIdentityResetResult.BlockedByActiveNearbyRooms(1), result)
        assertTrue(keyStore().containsAlias(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS))
        assertEquals(identity.keyId, available(provider().getOrCreateIdentity()).keyId)
    }

    @Test
    fun api31_successfulResetDoesNotGenerateReplacementUntilNextRequest() {
        assumeApi31()
        val original = available(provider().getOrCreateIdentity())

        assertTrue(provider().resetIdentity(0) is RecipientIdentityResetResult.Success)
        assertFalse(keyStore().containsAlias(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS))
        assertFalse(metadata().contains(AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_CREATED))

        val replacement = available(provider().getOrCreateIdentity())
        assertFalse(original.keyId == replacement.keyId)
    }

    @Test
    fun api21To30ReturnsUnsupportedWithoutCreatingAliasOrSoftwareFallback() {
        assumeFalse(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

        assertTrue(provider().getOrCreateIdentity() is RecipientEncryptionIdentityStatus.UnsupportedPlatform)
        assertTrue(provider().resolvePrivateKey("any") is RecipientPrivateKeyResult.UnsupportedPlatform)
        assertTrue(provider().resetIdentity(0) is RecipientIdentityResetResult.UnsupportedPlatform)
        assertFalse(keyStore().containsAlias(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS))
        assertFalse(metadata().contains(AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_CREATED))
    }

    private fun provider() = AndroidKeystoreRecipientEncryptionIdentityProvider(context)

    private fun available(status: RecipientEncryptionIdentityStatus): RecipientEncryptionIdentity =
        (status as RecipientEncryptionIdentityStatus.Available).identity

    private fun assumeApi31() = assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun metadata() = context.getSharedPreferences(
        AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_PREFS,
        Context.MODE_PRIVATE,
    )

    private fun writeValidMetadata(keyId: String) {
        metadata().edit()
            .putBoolean(AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_CREATED, true)
            .putInt(AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_VERSION, 1)
            .putString(
                AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_ALGORITHM,
                PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM.wireValue,
            )
            .putString(AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_KEY_ID, keyId)
            .commit()
    }

    private fun clearIdentityMetadataForTest(): Boolean = metadata().edit()
        .remove(AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_CREATED)
        .remove(AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_VERSION)
        .remove(AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_ALGORITHM)
        .remove(AndroidKeystoreRecipientEncryptionIdentityProvider.METADATA_KEY_ID)
        .commit()

    private fun writeResetStateForTest(state: String): Boolean = metadata().edit()
        .putInt(
            AndroidKeystoreRecipientEncryptionIdentityProvider.RESET_STATE_VERSION_KEY,
            AndroidKeystoreRecipientEncryptionIdentityProvider.RESET_STATE_VERSION,
        )
        .putString(AndroidKeystoreRecipientEncryptionIdentityProvider.RESET_STATE_KEY, state)
        .commit()

    private fun clearIdentity() {
        runCatching { keyStore().deleteEntry(AndroidKeystoreRecipientEncryptionIdentityProvider.KEY_ALIAS) }
        metadata().edit().clear().commit()
    }
}
